# OpenSSH Transport Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the in-process sshj relay with bundled OpenSSH driven by unison's native `ssh://` roots, deleting the tunnel layer and the sshj/bcprov dependencies.

**Architecture:** Cross-compile OpenSSH portable + static OpenSSL with the existing NDK pipeline and ship `ssh`/`ssh-keygen`/`ssh-keyscan` as `lib*.so` executables in jniLibs. Profiles emit `root = ssh://user@host/path` plus an `ssh =` command line; unison spawns and manages ssh itself. A proactive TOFU gate (keyscan → prompt → app-owned known_hosts) runs before the sync; the engine's tunnel machinery, relay, and watcher disappear.

**Tech Stack:** Kotlin, OpenSSH portable 10.2p1, OpenSSL 3.5.x (static), Android NDK 29.0.14206865 (API 26), Gradle version catalog.

**Spec:** `docs/superpowers/specs/2026-10-09-openssh-transport-design.md`

## Global Constraints

- minSdk 26; NDK 29.0.14206865; native builds target Android API 26, both arm64-v8a and x86_64.
- Pin `OPENSSH_VERSION=10.2p1`; OpenSSL from the 3.5.x line.
- SSH_EXEC transport only; `Profile` keeps `transport`/`remoteSocketPort` fields deserialized-and-ignored (JSON compatibility).
- No new runtime dependencies; sshj, bcprov, and sshd-core are removed by the final task.
- Conventional commits (`build:`, `feat:`, `fix:`, `test:`, `chore:`) matching `git log` style.
- Unit tests must not exec Android binaries; real-binary coverage lives in androidTest (on-device).

## Review Focus

1. **RSA-only legacy server** (no ed25519 host key): gate must approve whatever key type is scanned — Task 5 test `scan without ed25519 approves first scanned key`.
2. **Lost/corrupt known_hosts while HostKeyStore says approved**: ssh fails, engine maps "Host key verification failed" to TUNNEL, no crash — Task 6 test `host key verification failure maps to TUNNEL`.
3. **Passphrase-protected or malformed PEM import**: rejected with `KeyVaultException`, not a hang or crash — Task 3 test `import rejects key keygen cannot read`.
4. **Spaces/unicode in roots** (`/home/Diamond/my folder ♥`): prf lines still parse — Task 4 test `roots with spaces and unicode are emitted verbatim`.
5. **Non-22 SSH port**: port appears only as `-p` in the ssh line and in keyscan, never in the `ssh://` root — Task 4 and Task 5 port assertions.

---

### Task 1: Cross-compile OpenSSH (`native/build-openssh.sh`)

**Files:**
- Create: `native/build-openssh.sh`
- Outputs: `native/out/<abi>/libssh.so`, `libssh-keygen.so`, `libssh-keyscan.so` → installed to `app/src/main/jniLibs/<abi>/`

**Interfaces:**
- Produces: executables at `nativeLibraryDir/libssh.so`, `libssh-keygen.so`, `libssh-keyscan.so` (Tasks 3, 6 consume via `ProcessSshTool`/`SshCommand`).

- [ ] **Step 1: Write `native/build-openssh.sh`**

Mirror `build-unison.sh` structure (pins, NDK paths, `download()`, idempotent outputs, `install_to_jni`). Per ABI (`arm64-v8a` → `aarch64-linux-android`, `x86_64` → `x86_64-linux-android`):

1. Build static OpenSSL 3.5.x: `./Configure android-arm64` (or `android-x86_64`) `-static no-tests no-docs no-shared --prefix=$WORK/openssl-$TRIPLE`, `make -j install_sw`. Use the NDK clang with `ANDROID_API=26` env vars.
2. Build OpenSSH 10.2p1 (`https://cdn.openbsd.org/pub/OpenBSD/OpenSSH/portable/openssh-10.2p1.tar.gz`):
   `./configure --host=$TRIPLE --with-ssl-dir=$OPENSSL_PREFIX --without-openssl-header-check --disable-lastlog --disable-utmp --disable-utmpx --disable-wtmp --disable-wtmpx --disable-libutil --disable-etc-default-login CC=$NDK_CC` (no zlib: `--without-zlib`), then `make -j ssh ssh-keygen ssh-keyscan`.
3. `llvm-strip` the three binaries; copy to `native/out/$ABI/` and jniLibs as `libssh.so`, `libssh-keygen.so`, `libssh-keyscan.so`.

- [ ] **Step 2: Build both ABIs**

Run: `bash native/build-openssh.sh arm64-v8a && bash native/build-openssh.sh x86_64`
Expected: both exit 0, six files in `app/src/main/jniLibs/`.

- [ ] **Step 3: Verify artifacts are Android executables**

Run: `file app/src/main/jniLibs/arm64-v8a/libssh.so`
Expected: "ELF 64-bit LSB pie executable, ARM aarch64". Same class for x86_64 (`x86-64`).

- [ ] **Step 4: On-device smoke (spike gate — do not proceed past this task if it fails)**

With the device attached: `adb push app/src/main/jniLibs/arm64-v8a/libssh.so /data/local/tmp/ssh && adb shell chmod +x /data/local/tmp/ssh && adb shell /data/local/tmp/ssh -V`
Expected: `OpenSSH_10.2, OpenSSL ...`. Also `adb shell /data/local/tmp/ssh -o BatchMode=yes -F none veryshiny.net true` from adb's shell uid is a bonus check; the authoritative app-uid check happens in Task 8.

- [ ] **Step 5: Commit**

```bash
git add native/build-openssh.sh app/src/main/jniLibs/
git commit -m "build: cross-compile openssh 10.2p1 for android (ssh, keygen, keyscan)"
```

### Task 2: `SshTool` abstraction + KeyVault migration off BouncyCastle

**Files:**
- Create: `app/src/main/kotlin/io/unisondroid/app/sync/SshTool.kt`
- Modify: `app/src/main/kotlin/io/unisondroid/app/data/KeyVault.kt`
- Create: `app/src/test/kotlin/io/unisondroid/app/sync/FakeSshTool.kt`
- Move: `generateEd25519OpenSshKeyPem` from `KeyVault.kt` to `app/src/test/kotlin/io/unisondroid/app/data/TestKeys.kt` (still used by `SshTunnelTest` until Task 6 deletes it)
- Test: `app/src/test/kotlin/io/unisondroid/app/data/KeyVaultTest.kt` (rewrite)

**Interfaces:**
- Produces (`SshTool.kt`):

```kotlin
data class GeneratedKey(val privatePem: String, val publicKeyLine: String)
data class HostKeyEntry(val knownHostsLine: String, val keyType: String, val fingerprint: String)

class SshToolException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface SshTool {
    fun generateKey(comment: String): GeneratedKey
    fun derivePublicKey(pem: String): String
    fun scanHostKeys(host: String, port: Int): List<HostKeyEntry>
}

class ProcessSshTool(
    private val keygen: File,
    private val keyscan: File,
    private val workDir: File,
) : SshTool
```

- `ProcessSshTool.generateKey`: `keygen -t ed25519 -N "" -C <comment> -f <workDir tmp>`; read back PEM and `<tmp>.pub` line (append comment if keygen's line lacks it). `derivePublicKey`: write PEM to a 0600 temp, `keygen -y -f <tmp>`; nonzero exit or empty output → `SshToolException`. `scanHostKeys`: `keyscan -T 5 -t ed25519,ecdsa-sha2-nistp256,rsa-sha2-256,rsa-sha2-512 -p <port> <host>`; empty output → `SshToolException("no host keys")`; for each line, fingerprint via `keygen -lf -` (stdin), parse `<bits> SHA256:<b64> <type>`.
- `KeyVault(store, cipher, tool: SshTool)`: `generate` → `tool.generateKey(name)`; `importOpenSsh` → `tool.derivePublicKey(pem)` (catch `SshToolException` → `KeyVaultException("Malformed OpenSSH private key", e)`), then `publicLine(type, blobBase64, name)` built from the derived line's type + base64. Public API (`keys()`, `privateKeyPem()`) unchanged.
- Consumes: none new.

- [ ] **Step 1: Write failing KeyVaultTest with `FakeSshTool`**

Tests: `generate persists and round-trips via privateKeyPem` (fake returns a fixture PEM + `ssh-ed25519 AAAA... name` line); `importOpenSsh persists derived public key` (fake `derivePublicKey` returns `"ssh-ed25519 BBBB"`; stored `publicKey` is `"ssh-ed25519 BBBB <name>"`); `import rejects key keygen cannot read` (fake throws `SshToolException` → assert `KeyVaultException`); decryption-failure path unchanged.

- [ ] **Step 2: Run, verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.data.KeyVaultTest"`
Expected: FAIL — no `SshTool` type.

- [ ] **Step 3: Implement `SshTool.kt`, `ProcessSshTool`, migrate `KeyVault`, move `generateEd25519OpenSshKeyPem` to `TestKeys.kt`**

- [ ] **Step 4: Run, verify pass + full suite compiles**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (SshTunnelTest still compiles via `TestKeys`).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/io/unisondroid/app/sync/SshTool.kt app/src/main/kotlin/io/unisondroid/app/data/KeyVault.kt app/src/test/
git commit -m "feat: generate and validate ssh keys via bundled keygen (drop bcprov from keyvault)"
```

### Task 3: PrfGenerator emits `ssh://` root + `ssh =` command

**Files:**
- Modify: `app/src/main/kotlin/io/unisondroid/app/sync/PrfGenerator.kt`
- Test: `app/src/test/kotlin/io/unisondroid/app/sync/PrfGeneratorTest.kt` (rewrite)

**Interfaces:**
- Produces:

```kotlin
data class SshCommand(
    val binary: File,
    val keyFile: File,
    val knownHosts: File,
    val port: Int,
)

object PrfGenerator {
    fun generate(profile: Profile, ssh: SshCommand): String
}
```

- Consumes: `Profile` fields `user`, `host`, `sshPort`, `remoteRoot`, `serverCommand`, existing `localRoot`/`ignorePatterns`/`advancedPrefs`.

- [ ] **Step 1: Write failing tests**

Assert exact emitted lines for a profile with `user=Diamond host=veryshiny.net sshPort=2222 remoteRoot=/home/Diamond/unisondroid-e2e serverCommand=unison`:
`root = <localRoot>`, `root = ssh://Diamond@veryshiny.net/home/Diamond/unisondroid-e2e`, and the ssh line
`ssh = <binary> -F none -i <keyFile> -o UserKnownHostsFile=<knownHosts> -o StrictHostKeyChecking=yes -o BatchMode=yes -o IdentitiesOnly=yes -o LogLevel=ERROR -p 2222` (spaces in file paths must remain unquoted — unison quotes the whole line when invoking the shell). Additional tests: `servercmd` line emitted only when `serverCommand != "unison"` (`servercmd = /usr/local/bin/unison`); `roots with spaces and unicode are emitted verbatim` (localRoot `/storage/emulated/0/my folder ♥`, remoteRoot `/home/Diamond/my folder ♥`); no `socket://` root anywhere; default port 22 appears only as `-p 22`.

- [ ] **Step 2: Run, verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.sync.PrfGeneratorTest"`
Expected: FAIL — signature mismatch.

- [ ] **Step 3: Implement**

Keep `perms = 0 / links = false / fat = true`, ignore, and advancedPrefs blocks unchanged.

- [ ] **Step 4: Run, verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.sync.PrfGeneratorTest"`
Expected: PASS (LocalSyncE2eTest may fail — it is fixed in Task 6's sweep or now by updating `writeGeneratedProfile` to replace the `ssh://` root line with `root = <rootB>`; prefer fixing now).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/io/unisondroid/app/sync/PrfGenerator.kt app/src/test/ app/src/androidTest/
git commit -m "feat: emit unison-native ssh roots and ssh command line in profiles"
```

### Task 4: `HostKeyGate` — proactive TOFU

**Files:**
- Create: `app/src/main/kotlin/io/unisondroid/app/sync/HostKeyGate.kt`
- Test: `app/src/test/kotlin/io/unisondroid/app/sync/HostKeyGateTest.kt`

**Interfaces:**
- Consumes: `SshTool.scanHostKeys`, `HostKeyStore.known/approve` (`io.unisondroid.app.data`).
- Produces:

```kotlin
sealed interface HostKeyOutcome {
    data object Trusted : HostKeyOutcome
    data class Declined(val fingerprint: String) : HostKeyOutcome
    data class TimedOut(val fingerprint: String) : HostKeyOutcome
    data class ScanFailed(val detail: String) : HostKeyOutcome
}

class HostKeyGate(
    private val tool: SshTool,
    private val hostKeys: HostKeyStore,
    private val knownHostsFile: File,
) {
    suspend fun ensureTrusted(
        host: String,
        port: Int,
        decisionTimeoutMs: Long,
        prompt: suspend (fingerprint: String) -> Boolean,
    ): HostKeyOutcome
}
```

- Behavior: known host (`hostKeys.known(host, port) != null`) → `Trusted` without scanning. Unknown: scan; prefer the `ssh-ed25519` entry (else first) — that's the fingerprint prompted and the line persisted; on approve: append `knownHostsLine` to `knownHostsFile` (create parents, 0600) and `hostKeys.approve(host, port, fingerprint)` → `Trusted`. Decline → `Declined`; no decision within `decisionTimeoutMs` → `TimedOut`; `SshToolException` → `ScanFailed(detail)`.

- [ ] **Step 1: Write failing tests** (`FakeSshTool` + real `HostKeyStore`/`JsonStore` on `@TempDir`)

`unknown host scans prompts and persists on approval` (assert known_hosts file contains the raw line with 0600 perms, store has fingerprint); `declined prompt returns Declined and persists nothing`; `prompt timeout returns TimedOut` (virtual time via `runTest` + `advanceTimeBy`); `known host returns Trusted without scanning` (fake records calls; scan never invoked); `scan failure returns ScanFailed`; `scan without ed25519 approves first scanned key`; `keyscan is asked for the profile port` (fake asserts `port == 2222`).

- [ ] **Step 2: Run, verify failure** — `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.sync.HostKeyGateTest"` → FAIL (no class).

- [ ] **Step 3: Implement `HostKeyGate`**

- [ ] **Step 4: Run, verify pass** — same command → PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/io/unisondroid/app/sync/HostKeyGate.kt app/src/test/
git commit -m "feat: proactive tofu host-key gate over ssh-keyscan"
```

### Task 5: SyncEngine rewrite — no tunnel, one process

**Files:**
- Modify: `app/src/main/kotlin/io/unisondroid/app/sync/SyncEngine.kt`
- Modify: `app/src/main/kotlin/io/unisondroid/app/sync/UnisonRunner.kt` (`BinaryLocator` gains `fun locateSsh(): BinaryStatus` for `libssh.so`)
- Modify: `app/src/main/kotlin/io/unisondroid/app/service/SyncService.kt` (`buildEngine` wiring)
- Test: `app/src/test/kotlin/io/unisondroid/app/sync/SyncEngineTest.kt` (rewrite)

**Interfaces:**
- Consumes: `SshTool`, `HostKeyGate`, `SshCommand`/`PrfGenerator.generate(profile, ssh)`, `BinaryLocator.locateSsh()`, existing `KeyVault.privateKeyPem`, `UnisonRunner`, `OutputParser`.
- Produces:

```kotlin
open class SyncEngine(
    private val binaryLocator: BinaryLocator,
    private val profiles: ProfileRepository,
    private val keys: KeyVault,
    private val hostKeys: HostKeyStore,
    private val sshTool: SshTool,
    private val runnerFactory: (File) -> UnisonRunner,
    private val parserFactory: () -> OutputParser,
    private val unisonDir: File,
    private val sshHome: File,
    private val clock: Clock,
    private val hostKeyDecisionTimeoutMs: Long = HOST_KEY_DECISION_TIMEOUT_MS,
)
```

- `runSync` order: binary checks (unison + ssh → `BINARY_MISSING` with the missing name in detail) → `Connecting` → `HostKeyGate.ensureTrusted(host, port, timeoutMs) { fingerprint → emit AwaitingHostKey, await pendingDecision }` → outcomes: `Declined` → AUTH (existing declined detail), `TimedOut` → TUNNEL (existing timeout detail), `ScanFailed` → TUNNEL (`Could not scan host keys: <detail>`) → decrypt key → write `sshHome/<sshKeyId>` (0600, parents 0700) → write prf → start unison → collect output → `exitCode()` awaited directly (no settled/watcher) → finalize with existing taxonomy.
- Output markers during collection (mirroring `permissionDenied`): `Permission denied (publickey` → AUTH on failure; `Host key verification failed` → TUNNEL on failure; `Lost connection with the server` → TUNNEL on failure (ssh exit 255 / connect errors surface through unison this way).
- `finally`: delete the key file (also on failure/cancel), `activeJob = null`.
- `mapException`: sshj exception imports removed; `CancellationException` handling unchanged; default `UNKNOWN`.
- `SyncService.buildEngine`: `sshHome = File(context.noBackupFilesDir, "ssh")`, `sshTool = ProcessSshTool(File(nativeLibraryDir, "libssh-keygen.so"), File(nativeLibraryDir, "libssh-keyscan.so"), context.cacheDir)`.

- [ ] **Step 1: Rewrite SyncEngineTest with the new seams**

Keep adapted versions of: stale-lock clearing, happy path ordering (tunnel-open event becomes gate Trusted; assert prf content contains the ssh line and `ssh://` root; assert key file existed at runner start and is deleted after), unknown-host-key pause/approve/decline/timeout (via fake gate `prompt` suspension, no tunnel), cancel, binary missing (now also when `libssh.so` absent), nonzero exit mapping, version mismatch, local permissions, auth-marker mapping (`Permission denied (publickey...` in output → AUTH), `host key verification failure maps to TUNNEL`, `lost connection output maps to TUNNEL`, log cap, lastResult persistence. Delete all tunnel/watcher tests, `FakeTunnel`/`FakeHandle`, and `tunnelCloseGraceMs`.

- [ ] **Step 2: Run, verify failure** — `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.sync.SyncEngineTest"` → FAIL (constructor mismatch).

- [ ] **Step 3: Implement the rewrite + wiring**

- [ ] **Step 4: Run full unit suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS except `SshTunnelTest` still present-and-green (untouched; deleted in Task 6).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/ app/src/test/
git commit -m "feat: sync engine over unison-native ssh; delete tunnel lifecycle"
```

### Task 6: Delete the tunnel layer, sshj/bcprov deps, and UI trim

**Files:**
- Delete: `app/src/main/kotlin/io/unisondroid/app/sync/SshTunnel.kt`, `app/src/main/kotlin/io/unisondroid/app/sync/SshCrypto.kt`, `app/src/test/kotlin/io/unisondroid/app/sync/SshTunnelTest.kt`, `app/src/test/kotlin/io/unisondroid/app/data/TestKeys.kt` (if now unused), `app/src/androidTest/kotlin/io/unisondroid/app/SshCryptoTest.kt`
- Modify: `app/build.gradle.kts` (remove `libs.sshj`, `libs.bcprov`, `testImplementation(libs.sshd.core)`), `gradle/libs.versions.toml` (remove sshj/bouncyCastle/sshd entries), `app/src/main/kotlin/io/unisondroid/app/ui/ProfileEditorScreen.kt` (remove transport toggle, socket-port field, `Transport` import, `FIELD_SOCKET_PORT`/`TRANSPORT_*` constants; `Profile` still constructed with `transport = Transport.SSH_EXEC` default and existing `remoteSocketPort` preserved on edit)
- Test: existing suites

**Interfaces:**
- Consumes: none.
- Produces: sshj-free main source set.

- [ ] **Step 1: Delete and modify the listed files**

- [ ] **Step 2: Full unit suite green**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Grep confirms no sshj/bouncycastle references**

Run: `rg -n "sshj|bouncycastle|schmizz" app/src gradle/ || echo CLEAN` → `CLEAN`.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: remove sshj relay layer and bouncycastle; ssh-exec only"
```

### Task 7: On-device real-binary tests

**Files:**
- Create: `app/src/androidTest/kotlin/io/unisondroid/app/SshBinarySmokeTest.kt`
- Modify: `app/src/androidTest/kotlin/io/unisondroid/app/E2eProvisioningTest.kt` (unchanged API; now exercises keygen via `SshTool`)

**Interfaces:**
- Consumes: `ProcessSshTool` with `nativeLibraryDir` binaries.

- [ ] **Step 1: Write `SshBinarySmokeTest`**

Instrumented tests: `ssh binary reports its version` (exec `libssh.so -V`, assert output contains `OpenSSH_10.2`); `keygen generates and derives round-trip` (`ProcessSshTool.generateKey("smoke")` → `derivePublicKey(pem)` nonempty, type `ssh-ed25519`); `keyscan against localhost fails cleanly` (nothing listening on a free port → `SshToolException`).

- [ ] **Step 2: Run on device**

Run: `bash -c 'set -a; source .keyenv; set +a; ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.unisondroid.app.SshBinarySmokeTest'`
Expected: all PASS on the attached device.

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/
git commit -m "test: on-device smoke tests for bundled openssh binaries"
```

### Task 8: End-to-end verification on device

**Files:**
- Create: `app/src/androidTest/kotlin/io/unisondroid/app/SshE2eTest.kt` (real-server, env-gated)
- Test target: physical device + `veryshiny.net`

**Interfaces:**
- Consumes: full app stack.

- [ ] **Step 1: Write env-gated `SshE2eTest`**

Skips unless `E2E_HOST`/`E2E_USER`/`E2E_REMOTE_ROOT` instrumentation args are set. Provisions key + profile (reuse `E2eProvisioningTest` helpers), runs one sync of a small file each direction via the engine, asserts `SyncState.Finished`. This is the CI-optional / local-verification test.

- [ ] **Step 2: Manual big-file verification (the case that shipped both bugs)**

1. Build + install debug (release-signed), run the app's sync on the e2e profile with a fresh ≥100MB file on the server (`dd if=/dev/urandom ...`).
2. Expect UI `Sync finished`, local file md5 equal to server md5, profile `lastResult: OK`.
3. Capture `adb logcat -d -s System.err` — expect clean ssh session, no relay threads by definition.

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/
git commit -m "test: real-server e2e over bundled openssh"
```
