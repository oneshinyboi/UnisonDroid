# Complexity Offload and Background Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace three hand-rolled subsystems with upstream tooling (OpenSSH host-key storage, `ssh_config`, Tink) and route manual and scheduled sync through one WorkManager-hosted execution path.

**Architecture:** `SyncWorker` (CoroutineWorker) is the only sync host; `SyncScheduler` maps profiles + settings onto WorkManager work; `SyncEngine` gains an `INTERACTIVE`/`UNATTENDED` mode and a `SyncOutcome`. `SyncService` is deleted. OpenSSH's `known_hosts` becomes the only trust store, ssh options move to a generated `ssh_config`, and Tink replaces the hand-written AES-GCM cipher.

**Tech Stack:** Kotlin 2.4.20, AGP 9.4.1, Jetpack Compose, kotlinx.serialization/coroutines, AndroidX WorkManager, Google Tink, bundled OpenSSH 10.2p1 + Unison 2.53.8.

**Spec:** `docs/superpowers/specs/2026-10-09-complexity-offload-and-background-sync-design.md`

## Global Constraints

- `minSdk = 26`, `targetSdk = 36`, `compileSdk = 36`.
- WorkManager minimum periodic interval is 15 minutes; always clamp `autoSyncIntervalMinutes` to `>= 15`.
- The running foreground service type is `dataSync`; permissions `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS` already exist. Add `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
- ssh must be invoked with a single `-F <config>`; the config must contain `GlobalKnownHostsFile /dev/null` and `StrictHostKeyChecking yes`.
- Tink AEAD template is `AES256_GCM`; master key URI `android-keystore://unisondroid-master`; keyset shared-pref name `unisondroid-keyset`.
- No migration is required; previously stored key ciphertext will no longer decrypt.
- Unit tests: `./gradlew :app:testDebugUnitTest`. Instrumented: `./gradlew :app:connectedDebugAndroidTest` (x86_64 emulator).
- Follow existing test style: JUnit 5 (`org.junit.jupiter`) for unit tests, Robolectric for Android-facing units, `@TempDir` for file fixtures.

## Review Focus

Inputs/conditions the spec implies but may go untested — each line's test is added to the owning task:

1. Unknown host key during `UNATTENDED` must skip cleanly — no scan, no prompt, no auto-trust, no hang (Task 5).
2. `ssh-keygen -F` must query `[host]:port` for non-default ports, not bare `host` (Task 1).
3. A second sync while one is running must return `BUSY` and not start a second process (Task 5).
4. A changed host key must never be auto-accepted, in either mode (Task 5).
5. `setForeground` throwing (e.g. FGS start denied / notification permission missing) must not crash the worker (Task 7).

---

### Task 1: `SshTool.isHostKnown` via `ssh-keygen -F`

**Files:**
- Modify: `app/src/main/kotlin/io/unisondroid/app/sync/SshTool.kt`
- Modify: `app/src/test/kotlin/io/unisondroid/app/sync/FakeSshTool.kt`
- Test: `app/src/test/kotlin/io/unisondroid/app/sync/ProcessSshToolTest.kt`

**Interfaces:**
- Produces: `SshTool.isHostKnown(host: String, port: Int, knownHosts: File): Boolean`; `ProcessSshTool` implements it by running the bundled `ssh-keygen -F "[host]:port" -f <knownHosts>` and returning `exitCode == 0`.

- [ ] **Step 1: Write the failing test** in `ProcessSshToolTest.kt`. Add a test that a port-2222 query passes `[veryshiny.net]:2222` to the tool and returns `true` on exit 0:

```kotlin
@Test
fun `isHostKnown uses bracketed host colon port and reports found`() {
    val keygen = script("find-keygen", "test \"$1\" = '-F' || exit 9; echo found; exit 0")
    val tool = ProcessSshTool(keygen, keygen, dir)
    assertTrue(tool.isHostKnown("veryshiny.net", 2222, File(dir, "known_hosts")))
}
```

Add a second test where the script `exit 1` and assert `false`. (A real `-F` miss exits 1; a coding error exits differently, so assert only the boolean.)

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.sync.ProcessSshToolTest"`
Expected: FAIL — `isHostKnown` is not a member of `SshTool`.

- [ ] **Step 3: Implement** in `SshTool.kt`:

```kotlin
interface SshTool {
    fun generateKey(comment: String): GeneratedKey
    fun derivePublicKey(pem: String): String
    fun scanHostKeys(host: String, port: Int): List<HostKeyEntry>
    fun isHostKnown(host: String, port: Int, knownHosts: File): Boolean
}
```

In `ProcessSshTool`, add `hostSpec(host, port) = if (port == 22) host else "[$host]:$port"` and:

```kotlin
override fun isHostKnown(host: String, port: Int, knownHosts: File): Boolean {
    val process = ProcessBuilder(keygen.absolutePath, "-F", hostSpec(host, port), "-f", knownHosts.absolutePath)
        .redirectInput(ProcessBuilder.Redirect.from(File(DEV_NULL)))
        .start()
    drain(process, "ssh-keygen", "querying known_hosts")
    return process.exitValue() == 0
}
```

Add `var hostIsKnown: Boolean = false` and a `knownCalls` list to `FakeSshTool`, returning the var:

```kotlin
override fun isHostKnown(host: String, port: Int, knownHosts: File): Boolean {
    knownCalls += Triple(host, port, knownHosts)
    return hostIsKnown
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.sync.ProcessSshToolTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/io/unisondroid/app/sync/SshTool.kt app/src/test/kotlin/io/unisondroid/app/sync/FakeSshTool.kt app/src/test/kotlin/io/unisondroid/app/sync/ProcessSshToolTest.kt
git commit -m "feat: query known_hosts via ssh-keygen -F"
```

---

### Task 2: OpenSSH `known_hosts` is the only host-key store

**Files:**
- Modify: `app/src/main/kotlin/io/unisondroid/app/sync/HostKeyGate.kt`
- Modify: `app/src/main/kotlin/io/unisondroid/app/sync/SyncEngine.kt`
- Modify: `app/src/main/kotlin/io/unisondroid/app/data/Models.kt`
- Modify: `app/src/main/kotlin/io/unisondroid/app/service/SyncService.kt`
- Delete: `app/src/main/kotlin/io/unisondroid/app/data/HostKeyStore.kt`
- Delete: `app/src/test/kotlin/io/unisondroid/app/data/HostKeyStoreTest.kt`
- Test: `app/src/test/kotlin/io/unisondroid/app/sync/HostKeyGateTest.kt`
- Test: `app/src/test/kotlin/io/unisondroid/app/sync/SyncEngineTest.kt`
- Test: `app/src/test/kotlin/io/unisondroid/app/service/SyncServiceTest.kt`

**Interfaces:**
- Consumes: `SshTool.isHostKnown(host, port, knownHosts)` (Task 1).
- Produces: `HostKeyGate(tool: SshTool, knownHostsFile: File)`; `SyncEngine` constructor loses its `hostKeys` parameter. `HostKeyOutcome` keeps `Trusted`, `Declined(fingerprint)`, `TimedOut(fingerprint)`, `ScanFailed(detail)`.

- [ ] **Step 1: Update tests first.** In `HostKeyGateTest.kt` change the constructor helper to `HostKeyGate(tool, File(dir, "ssh/known_hosts"))`; replace "known host" seeding with `tool.hostIsKnown = true`; replace `hostKeys.known(...)` assertions with `assertTrue(knownHosts.readText().contains(ed25519.knownHostsLine))`. Add:

```kotlin
@Test
fun `trusted host is reported without scanning`() = runTest {
    val tool = FakeSshTool().apply { hostIsKnown = true }
    val knownHosts = File(dir, "ssh/known_hosts")
    val outcome = HostKeyGate(tool, knownHosts).ensureTrusted("veryshiny.net", 2222, 300_000L) { true }
    assertEquals(HostKeyOutcome.Trusted, outcome)
    assertTrue(tool.scanCalls.isEmpty())
    assertEquals(listOf(Triple("veryshiny.net", 2222, knownHosts)), tool.knownCalls)
}
```

In `SyncEngineTest.kt`: drop `HostKeyStore`/`KnownHost`, drop the `hostKeys` constructor arg, seed trust with `FakeSshTool().apply { hostIsKnown = knownFingerprint != null }`, and change the two `h.hostKeys.known(...)` assertions to check the `known_hosts` file under `sshHome` (absent after decline/timeout).

In `SyncServiceTest.kt`: update `TestSyncEngine`'s `super(...)` call to drop the `HostKeyStore(...)` argument.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.sync.HostKeyGateTest" --tests "io.unisondroid.app.sync.SyncEngineTest" --tests "io.unisondroid.app.service.SyncServiceTest"`
Expected: FAIL — constructor / unresolved `HostKeyStore`.

- [ ] **Step 3: Implement.**

`HostKeyGate`:

```kotlin
class HostKeyGate(private val tool: SshTool, private val knownHostsFile: File) {
    suspend fun ensureTrusted(host: String, port: Int, decisionTimeoutMs: Long,
                              prompt: suspend (fingerprint: String) -> Boolean): HostKeyOutcome {
        if (tool.isHostKnown(host, port, knownHostsFile)) return HostKeyOutcome.Trusted
        // ... existing scan / select / prompt / appendKnownHost flow, minus HostKeyStore
    }
}
```

Delete the `hostKeys.approve(...)` call; keep `appendKnownHost(entry.knownHostsLine)`.

`SyncEngine`: remove the `hostKeys` constructor parameter and the `import ...HostKeyStore`; construct `HostKeyGate(sshTool, knownHosts)`.

`Models.kt`: delete `KnownHost` and keep `TofuVerdict`? Delete both `TofuVerdict` and `KnownHost` (they are only used by the deleted store). Delete `HostKeyStore.kt` and `HostKeyStoreTest.kt`.

`SyncService.kt`: its `buildEngine` no longer passes `hostKeys`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.sync.HostKeyGateTest" --tests "io.unisondroid.app.sync.SyncEngineTest" --tests "io.unisondroid.app.service.SyncServiceTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A app/src/main/kotlin/io/unisondroid/app app/src/test
git commit -m "refactor: store host trust only in OpenSSH known_hosts"
```

---

### Task 3: Generate `ssh_config` and pass `-F`

**Files:**
- Create: `app/src/main/kotlin/io/unisondroid/app/sync/SshConfig.kt`
- Modify: `app/src/main/kotlin/io/unisondroid/app/sync/PrfGenerator.kt`
- Modify: `app/src/main/kotlin/io/unisondroid/app/sync/SyncEngine.kt`
- Test: `app/src/test/kotlin/io/unisondroid/app/sync/SshConfigTest.kt`
- Test: `app/src/test/kotlin/io/unisondroid/app/sync/PrfGeneratorTest.kt`
- Test: `app/src/test/kotlin/io/unisondroid/app/sync/SyncEngineTest.kt`

**Interfaces:**
- Produces: `SshConfig.render(keyFile: File, knownHosts: File, port: Int): String`; `SshCommand(binary: File, configFile: File)` (replaces the old four-field shape).
- Consumes: nothing new.

- [ ] **Step 1: Write the failing test** `SshConfigTest.kt`:

```kotlin
@Test
fun `render emits a single Host block with the run paths`() {
    val out = SshConfig.render(File("/ssh/key-1"), File("/ssh/known_hosts"), 2222)
    assertEquals(
        "Host *\n" +
            "  IdentityFile /ssh/key-1\n" +
            "  UserKnownHostsFile /ssh/known_hosts\n" +
            "  GlobalKnownHostsFile /dev/null\n" +
            "  StrictHostKeyChecking yes\n" +
            "  BatchMode yes\n" +
            "  IdentitiesOnly yes\n" +
            "  LogLevel ERROR\n" +
            "  Port 2222\n",
        out,
    )
}
```

In `PrfGeneratorTest.kt`: change the helper to `SshCommand(binary = File(".../libssh.so"), configFile = File(".../ssh_config"))`. Replace the exact-output expectations: line 3 becomes `sshargs = /data/.../ssh_config` prefixed `-F ` (i.e. `sshargs = -F /data/data/io.unisondroid.app/no_backup/ssh/ssh_config`). Change `non-default port appears only in the ssh line` to assert the prf contains neither `2222` nor `:2222` (the port now lives in the config, tested by `SshConfigTest`).

In `SyncEngineTest.kt`: after a run, assert the config written under `sshHome` contains `Port $SSH_PORT` and that the prf's `sshargs` line starts with `sshargs = -F `.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.sync.SshConfigTest" --tests "io.unisondroid.app.sync.PrfGeneratorTest"`
Expected: FAIL — `SshConfig` / new `SshCommand` shape unresolved.

- [ ] **Step 3: Implement.**

Create `SshConfig.kt` as shown by the test (a single `StringBuilder`; no trailing blank line).

`PrfGenerator.kt`:

```kotlin
data class SshCommand(val binary: File, val configFile: File)
// in generate():
sb.append("sshcmd = ").append(ssh.binary.absolutePath).append('\n')
sb.append("sshargs = -F ").append(ssh.configFile.absolutePath).append('\n')
```

`SyncEngine.runSync`: after writing the key, write the config:

```kotlin
val configFile = File(sshHome, "ssh_config")
configFile.writeText(SshConfig.render(key, knownHosts, p.sshPort))
val sshCommand = SshCommand(binary = sshBinary, configFile = configFile)
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.sync.SshConfigTest" --tests "io.unisondroid.app.sync.PrfGeneratorTest" --tests "io.unisondroid.app.sync.SyncEngineTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/io/unisondroid/app/sync app/src/test/kotlin/io/unisondroid/app/sync
git commit -m "refactor: drive ssh options from a generated ssh_config"
```

---

### Task 4: Tink-backed key-at-rest cipher

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/kotlin/io/unisondroid/app/data/TinkKeyCipher.kt`
- Modify: `app/src/main/kotlin/io/unisondroid/app/data/KeyVault.kt` (delete `KeystoreAesCipher`)
- Modify: `app/src/main/kotlin/io/unisondroid/app/service/SyncService.kt` (`buildKeys`)
- Delete: `app/src/androidTest/kotlin/io/unisondroid/app/KeystoreAesCipherTest.kt`
- Test: `app/src/androidTest/kotlin/io/unisondroid/app/TinkKeyCipherTest.kt`

**Interfaces:**
- Produces: `TinkKeyCipher(context: Context) : KeyCipher`. `KeyCipher`, `KeyVault` unchanged.

- [ ] **Step 1: Add the dependency.** In `libs.versions.toml` add `tink = "1.16.0"` (or the current stable) and `tink-android = { group = "com.google.crypto.tink", name = "tink-android", version.ref = "tink" }`; in `app/build.gradle.kts` add `implementation(libs.tink.android)`.

- [ ] **Step 2: Write the failing instrumented test** `TinkKeyCipherTest.kt` (mirrors the old `KeystoreAesCipherTest`):

```kotlin
@Test fun encryptDecrypt_roundTrips() {
    val cipher = TinkKeyCipher(InstrumentationRegistry.getInstrumentation().targetContext)
    val plain = "hello tink".toByteArray() + ByteArray(32) { it.toByte() }
    assertArrayEquals(plain, cipher.decrypt(cipher.encrypt(plain)))
}

@Test fun encrypt_usesFreshCiphertextPerCall() {
    val cipher = TinkKeyCipher(InstrumentationRegistry.getInstrumentation().targetContext)
    val plain = "same input".toByteArray()
    assertFalse(cipher.encrypt(plain).contentEquals(cipher.encrypt(plain)))
}
```

Keep the existing `keyVault_generate_privateKeyPemRoundTrips` test from `KeystoreAesCipherTest`, swapping `KeystoreAesCipher()` for `TinkKeyCipher(context)`.

- [ ] **Step 3: Run to verify it fails**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "io.unisondroid.app.TinkKeyCipherTest"`
Expected: FAIL — `TinkKeyCipher` unresolved (or no emulator: skip to Step 4, then run it in Task 10's verification).

- [ ] **Step 4: Implement** `TinkKeyCipher.kt`:

```kotlin
class TinkKeyCipher(context: Context) : KeyCipher {
    private val aead: Aead = AndroidKeysetManager.Builder()
        .withSharedPref(context, "unisondroid-keyset", "unisondroid-keysets")
        .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
        .withMasterKeyUri("android-keystore://unisondroid-master")
        .build().keysetHandle.getPrimitive(Aead::class.java)
    override fun encrypt(plain: ByteArray): ByteArray = aead.encrypt(plain, null)
    override fun decrypt(blob: ByteArray): ByteArray = aead.decrypt(blob, null)
}
```

Delete `KeystoreAesCipher` from `KeyVault.kt` (keep `KeyCipher`). Update `SyncService.buildKeys` to `KeyVault(store(context), TinkKeyCipher(context), sshTool(context))`. Delete the old instrumented test file.

- [ ] **Step 5: Run unit tests and commit** (the cipher-critical path is instrumented; unit tests use the existing `KeyCipher` fakes)

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.data.KeyVaultTest"`
Expected: PASS.

```bash
git add -A gradle app/build.gradle.kts app/src
git commit -m "refactor: encrypt private keys with Tink"
```

---

### Task 5: `SyncMode` / `SyncOutcome` and unattended host-key handling

**Files:**
- Modify: `app/src/main/kotlin/io/unisondroid/app/sync/SyncEngine.kt`
- Modify: `app/src/main/kotlin/io/unisondroid/app/sync/HostKeyGate.kt`
- Modify: `app/src/main/kotlin/io/unisondroid/app/service/SyncService.kt` (temporary caller)
- Test: `app/src/test/kotlin/io/unisondroid/app/sync/HostKeyGateTest.kt`
- Test: `app/src/test/kotlin/io/unisondroid/app/sync/SyncEngineTest.kt`

**Interfaces:**
- Produces: `enum class SyncMode { INTERACTIVE, UNATTENDED }` and `enum class SyncOutcome { COMPLETED, FAILED, SKIPPED_UNTRUSTED, BUSY }`; `SyncEngine.requestSync(profileId: String, mode: SyncMode): SyncOutcome`; `HostKeyOutcome.Untrusted`; `HostKeyGate.ensureTrusted(host, port, interactive: Boolean, decisionTimeoutMs, prompt): HostKeyOutcome`.
- Consumes: Task 2's gate/store, Task 3's ssh config.

- [ ] **Step 1: Update tests.** In `SyncEngineTest.kt` change every `requestSync("profX")` to `requestSync("profX", SyncMode.INTERACTIVE)`, and the concurrency test to use `SyncMode.UNATTENDED` for the second call. Replace the boolean assertions (`assertFalse(proceeded)`) with `assertEquals(SyncOutcome.BUSY, second)`. Add:

```kotlin
@Test
fun `unattended sync with unknown host key skips without scanning or prompting`() = runTest {
    val h = harness(scripts = listOf(ScriptedProcess(lines = listOf(SUMMARY_LINE))), knownFingerprint = null)
    val outcome = withTimeout(10_000) { h.engine.requestSync("prof1", SyncMode.UNATTENDED) }
    assertEquals(SyncOutcome.SKIPPED_UNTRUSTED, outcome)
    assertTrue(h.runner.starts.isEmpty())
    assertTrue(h.sshTool.scanCalls.isEmpty(), "unattended must not scan")
}

@Test
fun `changed host key never completes even unattended`() = runTest {
    val h = harness(
        scripts = listOf(ScriptedProcess(lines = listOf("Host key verification failed."), exit = 255)),
    )
    val outcome = withTimeout(10_000) { h.engine.requestSync("prof1", SyncMode.UNATTENDED) }
    assertEquals(SyncOutcome.FAILED, outcome)
    assertEquals(SyncState.Reason.TUNNEL, (h.engine.state.value as SyncState.Failed).reason)
}
```

In `HostKeyGateTest.kt` add:

```kotlin
@Test
fun `unattended unknown host returns Untrusted without scanning`() = runTest {
    val tool = FakeSshTool().apply { hostIsKnown = false }
    val outcome = HostKeyGate(tool, File(dir, "ssh/known_hosts"))
        .ensureTrusted("veryshiny.net", 2222, interactive = false, 300_000L) { true }
    assertEquals(HostKeyOutcome.Untrusted, outcome)
    assertTrue(tool.scanCalls.isEmpty())
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.sync.SyncEngineTest" --tests "io.unisondroid.app.sync.HostKeyGateTest"`
Expected: FAIL — `SyncMode`/`requestSync` signature.

- [ ] **Step 3: Implement.** Add to `SyncEngine.kt`:

```kotlin
enum class SyncMode { INTERACTIVE, UNATTENDED }
enum class SyncOutcome { COMPLETED, FAILED, SKIPPED_UNTRUSTED, BUSY }
```

Change the signature and returns: `BINARY_MISSING` → set the `Failed` state and `return SyncOutcome.FAILED`; `if (!syncMutex.tryLock()) return SyncOutcome.BUSY`. `runSync` returns `SyncOutcome` and `requestSync` returns it: `Finished` → `COMPLETED`; any `Failed` → `FAILED`; `Untrusted` gate outcome → set `SyncState.Failed(profileId, SyncState.Reason.AUTH, hostKeyNeedsApprovalDetail(p))`, do **not** call `markFailed`, `return SyncOutcome.SKIPPED_UNTRUSTED`.

`HostKeyGate.ensureTrusted` adds `interactive: Boolean` after `port`:

```kotlin
if (tool.isHostKnown(host, port, knownHostsFile)) return HostKeyOutcome.Trusted
if (!interactive) return HostKeyOutcome.Untrusted
// scan + prompt as before
```

Add `data object Untrusted : HostKeyOutcome`.

`SyncService.kt`: update its `engine.requestSync(profileId)` call to `engine.requestSync(profileId, SyncMode.INTERACTIVE)` and treat `SyncOutcome.BUSY` as `false`.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.sync.SyncEngineTest" --tests "io.unisondroid.app.sync.HostKeyGateTest" --tests "io.unisondroid.app.service.SyncServiceTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/io/unisondroid/app app/src/test
git commit -m "feat: add interactive/unattended sync modes"
```

---

### Task 6: `AppSettings`, `SettingsRepository`, and profile schedule fields

**Files:**
- Create: `app/src/main/kotlin/io/unisondroid/app/data/AppSettings.kt`
- Modify: `app/src/main/kotlin/io/unisondroid/app/data/Models.kt`
- Test: `app/src/test/kotlin/io/unisondroid/app/data/SettingsRepositoryTest.kt`

**Interfaces:**
- Produces: `@Serializable data class AppSettings(val syncOnMobileData: Boolean = true)`; `SettingsRepository(store: JsonStore) { suspend fun get(): AppSettings; suspend fun set(settings: AppSettings) }` persisted as `settings` via `JsonStore`; `Profile.autoSyncEnabled: Boolean = false`, `Profile.autoSyncIntervalMinutes: Int = 60`.

- [ ] **Step 1: Write the failing test**

```kotlin
class SettingsRepositoryTest {
    @Test fun `defaults to allowing mobile data`(@TempDir dir: File) = runTest {
        assertEquals(AppSettings(syncOnMobileData = true), SettingsRepository(JsonStore(dir)).get())
    }
    @Test fun `set persists across reload`(@TempDir dir: File) = runTest {
        SettingsRepository(JsonStore(dir)).set(AppSettings(syncOnMobileData = false))
        assertEquals(AppSettings(syncOnMobileData = false), SettingsRepository(JsonStore(dir)).get())
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.data.SettingsRepositoryTest"`
Expected: FAIL — `AppSettings` unresolved.

- [ ] **Step 3: Implement.** Create `AppSettings.kt` with the data class and a `SettingsRepository` that reads `store.read<AppSettings>("settings") ?: AppSettings()` and writes `store.write("settings", settings)`. Add `autoSyncEnabled: Boolean = false` and `autoSyncIntervalMinutes: Int = 60` to `Profile` in `Models.kt` (with defaults, so existing JSON still decodes).

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.data.SettingsRepositoryTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/io/unisondroid/app/data/AppSettings.kt app/src/main/kotlin/io/unisondroid/app/data/Models.kt app/src/test/kotlin/io/unisondroid/app/data/SettingsRepositoryTest.kt
git commit -m "feat: add app settings and profile schedule fields"
```

---

### Task 7: `SyncWorker` + `SyncScheduler`, delete `SyncService`

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts` (WorkManager, work-testing)
- Create: `app/src/main/kotlin/io/unisondroid/app/service/SyncNotifications.kt`
- Create: `app/src/main/kotlin/io/unisondroid/app/service/SyncWorker.kt`
- Create: `app/src/main/kotlin/io/unisondroid/app/service/SyncScheduler.kt`
- Modify: `app/src/main/kotlin/io/unisondroid/app/service/SyncService.kt` → move `ServiceLocator` to its own file and delete the service
- Create: `app/src/main/kotlin/io/unisondroid/app/service/ServiceLocator.kt`
- Modify: `app/src/main/kotlin/io/unisondroid/app/ui/RunScreen.kt`
- Modify: `app/src/main/AndroidManifest.xml` (remove the `<service>` block)
- Delete: `app/src/test/kotlin/io/unisondroid/app/service/SyncServiceTest.kt`
- Create: `app/src/test/kotlin/io/unisondroid/app/service/ServiceLocatorTest.kt`
- Test: `app/src/test/kotlin/io/unisondroid/app/service/SyncSchedulerTest.kt`
- Test: `app/src/test/kotlin/io/unisondroid/app/service/SyncWorkerTest.kt`

**Interfaces:**
- Consumes: `SyncEngine.requestSync`/`SyncMode`/`SyncOutcome` (Task 5), `AppSettings` (Task 6).
- Produces: `SyncScheduler(context) { fun syncNow(profileId: String); fun reconcile(profiles: List<Profile>, settings: AppSettings) }`; `SyncWorker` reading `inputData` keys `"profileId"` and `"mode"`; work names `"sync-<id>"` (periodic) and `"sync-<id>-now"` (one-shot); `SyncNotifications(context) { fun foregroundInfo(text: String): ForegroundInfo; fun notifyFailure(profileId: String); fun notifyNeedsApproval(profileId: String) }`; `ServiceLocator` moved unchanged into `ServiceLocator.kt`.

- [ ] **Step 1: Add dependencies.** `work = "2.11.0"` (or current stable); `androidx-work-runtime-ktx = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "work" }`; `androidx-work-testing = { group = "androidx.work", name = "work-testing", version.ref = "work" }`. Add `implementation(libs.androidx.work.runtime.ktx)` and `testImplementation(libs.androidx.work.testing)`.

- [ ] **Step 2: Write the failing tests.**

`ServiceLocatorTest.kt` (Robolectric; move the caching test out of `SyncServiceTest`):

```kotlin
@RunWith(RobolectricTestRunner::class) @Config(sdk = [36])
class ServiceLocatorTest {
    @Test fun `engine is cached per provider`() { /* existing test body */ }
}
```

`SyncSchedulerTest.kt` (Robolectric + `WorkManagerTestInitHelper`, `TestDriver`):

```kotlin
@Test fun `enabling a profile enqueues periodic work with the mobile-data constraint`() { ... }
@Test fun `disabling a profile cancels its periodic work`() { ... }
@Test fun `reconcile clamps intervals below 15 minutes`() { ... }   // profile interval 5 -> 15
@Test fun `syncNow enqueues a one-shot INTERACTIVE request`() { ... }
```

Assert via `testDriver.getWorkInfosForUniqueWork("sync-p1").get()` and read `workInfo.constraints.requiredNetworkType` (`UNMETERED` when `syncOnMobileData = false`, else `CONNECTED`).

`SyncWorkerTest.kt` (`TestListenableWorkerBuilder`):

```kotlin
@Test fun `unattended skip posts a needs-approval notification and succeeds`() { ... }
@Test fun `busy result returns retry`() { ... }
@Test fun `foreground failure does not crash the worker`() { ... }   // Review Focus 5: setForeground throws -> still returns a Result
```

Use a fake engine via `ServiceLocator.engineProvider` that returns a stub `SyncEngine` overriding `requestSync` (like the old `TestSyncEngine`).

- [ ] **Step 3: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.service.SyncSchedulerTest" --tests "io.unisondroid.app.service.SyncWorkerTest"`
Expected: FAIL — classes unresolved.

- [ ] **Step 4: Implement.**

`SyncScheduler`:

```kotlin
class SyncScheduler(private val context: Context) {
    fun syncNow(profileId: String) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(SyncWorker.KEY_PROFILE_ID to profileId, SyncWorker.KEY_MODE to SyncMode.INTERACTIVE.name))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("sync-$profileId-now", ExistingWorkPolicy.KEEP, req)
    }
    fun reconcile(profiles: List<Profile>, settings: AppSettings) {
        profiles.forEach { p ->
            val name = "sync-${p.id}"
            if (!p.autoSyncEnabled) { WorkManager.getInstance(context).cancelUniqueWork(name); return@forEach }
            val minutes = maxOf(p.autoSyncIntervalMinutes, 15).toLong()
            val req = PeriodicWorkRequestBuilder<SyncWorker>(minutes, TimeUnit.MINUTES)
                .setInputData(workDataOf(SyncWorker.KEY_PROFILE_ID to p.id, SyncWorker.KEY_MODE to SyncMode.UNATTENDED.name))
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(if (settings.syncOnMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED)
                    .build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
```

`SyncWorker.doWork`: read keys, `runCatching { setForeground(notifications.foregroundInfo("Syncing…")) }`, then map the outcome (do not rethrow). `COMPLETED` → `success`; `SKIPPED_UNTRUSTED` → `notifyNeedsApproval` + `success`; `FAILED` → (`UNATTENDED`: `notifyFailure`) + `failure`; `BUSY` → `retry`; any throwable → `retry`.

`SyncNotifications` moves the channel (`"unison_sync"`) and `NOTIFICATION_ID = 1` out of `SyncService`, plus `foregroundInfo` returning `ForegroundInfo(NOTIFICATION_ID, notification(text, ongoing = true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)` and the two terminal notifications.

Move `ServiceLocator` verbatim into `ServiceLocator.kt`; delete `SyncService.kt` and its manifest entry.

`RunScreen`: replace the `LaunchedEffect { startForegroundService(...) }` with `LaunchedEffect(profileId) { SyncScheduler(context).syncNow(profileId) }`; drop the `SyncService` import. Keep the engine observation and POST_NOTIFICATIONS request.

- [ ] **Step 5: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.service.*" --tests "io.unisondroid.app.ui.RunScreenTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A app gradle
git commit -m "feat: host sync in a WorkManager worker and delete SyncService"
```

---

### Task 8: Profile editor controls and reconcile hooks

**Files:**
- Modify: `app/src/main/kotlin/io/unisondroid/app/ui/ProfileEditorScreen.kt`
- Modify: `app/src/main/kotlin/io/unisondroid/app/ui/ProfilesScreen.kt`
- Modify: `app/src/main/kotlin/io/unisondroid/app/MainActivity.kt`
- Test: `app/src/test/kotlin/io/unisondroid/app/ui/ProfileEditorScreenTest.kt`

**Interfaces:**
- Consumes: `Profile.autoSyncEnabled`/`autoSyncIntervalMinutes` (Task 6), `SyncScheduler.reconcile` (Task 7), `SettingsRepository` (Task 6).
- Produces: `ProfileEditorScreen` test tags `FIELD_AUTO_SYNC = "editor-autoSync"`, `FIELD_INTERVAL = "editor-autoSyncInterval"`; `AUTO_SYNC_INTERVALS_MINUTES = listOf(15, 30, 60, 180, 360, 720, 1440)`.

- [ ] **Step 1: Write the failing test** in `ProfileEditorScreenTest.kt`: with `initial = profile(autoSyncEnabled = true, autoSyncIntervalMinutes = 180)`, assert the switch is on and the interval control shows "3 h"; toggling the switch and saving yields `autoSyncEnabled = false` in the saved `Profile`.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.ui.ProfileEditorScreenTest"`
Expected: FAIL — unknown fields/tags.

- [ ] **Step 3: Implement.**

Add to `ProfileEditorContent.save()` the `autoSyncEnabled`/`autoSyncIntervalMinutes` state (defaulting from `initial`). Add to `ProfileEditorScreen.kt` a `Switch` (tag `FIELD_AUTO_SYNC`) and an interval `DropdownMenu` (tag `FIELD_INTERVAL`), labeled e.g. "15 min", "30 min", "1 h", "3 h", "6 h", "12 h", "24 h", shown only when the switch is on.

Reconcile after `repository.save(profile)` in `ProfileEditorScreen` and after `repository.delete(id)` in `ProfilesScreen`: `SyncScheduler(context).reconcile(repository.profiles(), ServiceLocator.settings(context).get())`. Add `ServiceLocator.settings(context)` returning a cached `SettingsRepository`.

In `MainActivity.onCreate`, after `super.onCreate`, launch `lifecycleScope.launch { SyncScheduler(this@MainActivity).reconcile(ServiceLocator.profiles(this@MainActivity).profiles(), ServiceLocator.settings(this@MainActivity).get()) }`.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.ui.ProfileEditorScreenTest" --tests "io.unisondroid.app.ui.ProfilesScreenTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/io/unisondroid/app app/src/test
git commit -m "feat: per-profile scheduled sync controls"
```

---

### Task 9: Settings screen for the mobile-data option

**Files:**
- Create: `app/src/main/kotlin/io/unisondroid/app/ui/SettingsScreen.kt`
- Modify: `app/src/main/kotlin/io/unisondroid/app/ui/Navigation.kt`
- Modify: `app/src/main/kotlin/io/unisondroid/app/ui/ProfilesScreen.kt` (add a Settings action)
- Test: `app/src/test/kotlin/io/unisondroid/app/ui/SettingsScreenTest.kt`

**Interfaces:**
- Consumes: `SettingsRepository` (Task 6), `SyncScheduler.reconcile` (Task 7).
- Produces: `Routes.SETTINGS = "settings"`; `SETTINGS_TOGGLE_TAG = "settings-syncOnMobileData"`; `PROFILES_SETTINGS_ACTION_TAG = "profiles-settings-action"`.

- [ ] **Step 1: Write the failing test** `SettingsScreenTest.kt`: render `SettingsContent(syncOnMobileData = false, onToggle = {})`, assert the switch is off; invoke the toggle, assert the callback fired.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.ui.SettingsScreenTest"`
Expected: FAIL — `SettingsContent` unresolved.

- [ ] **Step 3: Implement.** `SettingsContent(syncOnMobileData: Boolean, onToggle: (Boolean) -> Unit)` with a labeled `Switch` ("Don't sync on mobile data"). `SettingsScreen` loads `ServiceLocator.settings(context).get()` via `produceState`, and on toggle writes the new value then calls `SyncScheduler(context).reconcile(profiles, updated)`. Add `Routes.SETTINGS` and a `composable` in `Navigation.kt`, plus a "Settings" `TextButton` in the `ProfilesScreen` top bar wired to `onOpenSettings`.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.ui.SettingsScreenTest" --tests "io.unisondroid.app.ui.NavigationTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/io/unisondroid/app/ui app/src/test/kotlin/io/unisondroid/app/ui
git commit -m "feat: settings screen with mobile-data toggle"
```

---

### Task 10: Battery exemption + notification permission

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/kotlin/io/unisondroid/app/ui/Permissions.kt`
- Modify: `app/src/main/kotlin/io/unisondroid/app/ui/ProfileEditorScreen.kt`
- Test: `app/src/test/kotlin/io/unisondroid/app/ui/PermissionsTest.kt`

**Interfaces:**
- Produces: `fun batteryExemptionIntent(context: Context): Intent?` (returns the `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` intent, or `null` when already exempt), used when auto-sync is enabled.

- [ ] **Step 1: Write the failing test** `PermissionsTest.kt` (Robolectric): with `PowerManager.isIgnoringBatteryOptimizations` shadowed to `false`, `batteryExemptionIntent(context)` is non-null and targets `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; with it `true`, returns `null`.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.ui.PermissionsTest"`
Expected: FAIL — `batteryExemptionIntent` unresolved.

- [ ] **Step 3: Implement.** Add `<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />` to the manifest. Implement `batteryExemptionIntent` (guard `isIgnoringBatteryOptimizations`; `@SuppressLint("BatteryLife")`). In `ProfileEditorScreen`, when the auto-sync switch is turned on, request POST_NOTIFICATIONS if not granted (reuse the pattern in `RunScreen`) and launch `batteryExemptionIntent(context)` if non-null.

- [ ] **Step 4: Run to verify pass, then the instrumented suite**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.ui.PermissionsTest"`
Expected: PASS.
Run (with an x86_64 emulator): `./gradlew :app:connectedDebugAndroidTest`
Expected: PASS — includes `TinkKeyCipherTest` and the existing e2e tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/kotlin/io/unisondroid/app/ui app/src/test/kotlin/io/unisondroid/app/ui
git commit -m "feat: request battery exemption and notifications for auto-sync"
```

---

## Self-Review Notes

- **Spec coverage:** host-key unification (T1–T2), ssh_config (T3), Tink (T4), mode/outcome (T5), settings (T6), worker/scheduler + SyncService deletion (T7), per-profile scheduling + UI + reconcile (T8), settings UI (T9), permissions (T10). Notifications folded into T7 per the worker's responsibility. Native-build maintenance is a non-goal.
- **Review Focus tests:** items 1–5 are pinned to Tasks 1, 5, and 7 as noted.
- **Deferred/absent by YAGNI:** `SshTool.forgetHost` (no remove-trust UI yet), charging constraints, an "Auto" chip on profile rows.
- **Post-task manual check:** on-device, enable auto-sync for a profile, confirm the battery prompt appears, a scheduled run promotes to a foreground dataSync service, and an untrusted host produces a needs-approval notification rather than a run.
