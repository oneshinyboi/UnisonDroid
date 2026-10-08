# UnisonDroid Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A native Android app (UnisonDroid) that syncs phone folders with a user's unison 2.53.x server over an SSH-key-authenticated tunnel, by exec'ing an embedded cross-compiled unison binary.

**Architecture:** Compose UI → foreground `SyncService` → `SyncEngine` orchestrating `SshTunnel` (sshj local port-forward) + `UnisonRunner` (ProcessBuilder exec of `jniLibs/<abi>/libunison.so`). Pure-Kotlin seams (`PrfGenerator`, `OutputParser`, stores) are JVM-TDD'd; the real binary is only needed for instrumented e2e.

**Tech Stack:** Kotlin 2.x, Jetpack Compose (M3), kotlinx-serialization, kotlinx-coroutines, sshj (Apache-2.0), Apache MINA sshd (tests only), OCaml 4.14.x + Unison 2.53.x cross-compiled with the Android NDK.

**Spec:** `docs/superpowers/specs/2026-10-08-unisondroid-design.md` — read it first; this plan argues from it.

## Global Constraints

- License GPLv3. App name "UnisonDroid", `applicationId` `io.unisondroid.app`, package root `io.unisondroid.app`.
- Unison pinned to latest stable **2.53.x** tag (record exact tag as `UNISON_VERSION=` in `native/build-unison.sh`). OCaml **4.14.x**. NDK: current stable, full revision pinned in `native/build-unison.sh`. ABIs: **arm64-v8a + x86_64 only**.
- `minSdk 26`, `compileSdk`/`targetSdk 36`. Manifest includes: `MANAGE_EXTERNAL_STORAGE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`, `INTERNET`; service declares `android:foregroundServiceType="dataSync"`.
- Unison state dir is `context.noBackupFilesDir/unison` (archives must never be cloud-backed-up). Generated profiles land there as `<profileId>.prf`.
- One sync at a time, manual only. No plaintext socket mode ever ships.
- Generated prf Android defaults: `perms = 0`, `links = false`, `fat = true`; ignore patterns and the advanced block appended after defaults (advanced lines come last so they override).
- All commits conventional (`feat:`, `test:`, `build:`, `chore:`). Every task ends with a commit. Test command shorthand below: `gradle-test` = `./gradlew :app:testDebugUnitTest --tests`, `gradle-it` = `./gradlew :app:connectedDebugAndroidTest`.
- Gradle deps via `gradle/libs.versions.toml` (latest stable); test-only MINA sshd must not leak into `implementation` configs.

## Review Focus

1. **Missing/unextracted binary** (fresh install, weird devices): expect actionable "engine missing" state, not a crash. → Task 7 test `BinaryLocator` on absent file; Task 12 renders `SyncState.Failed(Reason.BinaryMissing)`.
2. **Partial/odd unison output**: `\r`-only progress updates, very long lines. Expect incremental feed without losing events. → Task 4 tests `feed` with CRLF fragments and `"\r"` mid-line.
3. **Tunnel drop mid-sync**: expect process killed, state `Failed`, no hang. → Task 8 test kills the fake tunnel while the fake process runs.
4. **Paths with spaces/unicode** (`/storage/emulated/0/My Folder/中文`): expect exact pass-through in prf and argv (no shell interpolation anywhere). → Task 3 pins prf rendering; Task 7 pins argv via a fake binary echoing args.
5. **Unbounded log growth**: expect capped event/log buffer. → Task 8 test pins the cap at 2000 lines.

---

### Task 1: Project scaffold

**Files:**
- Create: root `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, `.gitignore`, `README.md`
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/io/unisondroid/app/MainActivity.kt`, `app/src/main/kotlin/io/unisondroid/app/ui/theme/Theme.kt`
- Create: empty `docs/SERVER-SETUP.md` (heading only for now)

**Interfaces:**
- Produces: buildable Compose app module `:app`; `MainActivity` rendering "UnisonDroid"; Gradle wrapper committed; manifest already carrying every permission/flag from Global Constraints (service block added in Task 9; declare the service only then).

- [ ] **Step 1: Scaffold minimal Gradle/Compose project (AGP current stable, Kotlin 2.x, Compose BOM, version catalog, serialization + coroutines + sshj + test MINA sshd deps).**
- [ ] **Step 2: Verify:** `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL; `./gradlew :app:lintDebug` → no errors.
- [ ] **Step 3: Commit** `chore: scaffold Android/Compose project`.

### Task 2: Models, JsonStore, ProfileRepository

**Files:**
- Create: `app/src/main/kotlin/io/unisondroid/app/data/Models.kt`, `data/JsonStore.kt`, `data/ProfileRepository.kt`
- Test: `app/src/test/kotlin/io/unisondroid/app/data/JsonStoreTest.kt`, `data/ProfileRepositoryTest.kt`

**Interfaces:**
- Produces:
  - `@Serializable data class Profile(id: String, name: String, localRoot: String, remoteRoot: String, host: String, sshPort: Int = 22, user: String, remoteSocketPort: Int = 22333, sshKeyId: String, ignorePatterns: List<String> = emptyList(), advancedPrefs: String = "", lastSyncedAt: Long? = null, lastResult: SyncResult? = null)`
  - `enum class SyncResult { NEVER, OK, WARNINGS, FAILED }`
  - `@Serializable data class SshKey(id: String, name: String, publicKey: String, encryptedPrivateBase64: String)`
  - `@Serializable data class KnownHost(host: String, port: Int, fingerprint: String, approvedAt: Long)`
  - `class JsonStore(private val dir: File)` with `suspend fun <reified T> read(name: String): T?` and `suspend fun <reified T> write(name: String, value: T)` — atomic (temp+rename), Mutex-guarded.
  - `class ProfileRepository(private val store: JsonStore)` with `suspend fun profiles(): List<Profile>`, `suspend fun save(p: Profile)` (upsert by id), `suspend fun delete(id: String)`, `suspend fun get(id: String): Profile?`. IDs are `[a-z0-9-]{8}` slugs generated internally on save when absent.

- [ ] **Step 1: Failing tests** — `JsonStoreTest`: `write then read round-trips`; `read of missing file returns null`; `write is atomic (no temp files left)`. `ProfileRepositoryTest`: `save inserts and upserts`; `delete removes`; `get returns by id`; `ids are slugs`. Use `@TempDir` (JUnit5) or `kotlin.io.path.createTempDirectory`.
- [ ] **Step 2: Run** `gradle-test "io.unisondroid.app.data.*"` → FAIL (no classes).
- [ ] **Step 3: Implement** the three files. UUID-slug via `java.util.UUID.randomUUID()` trimmed to 8 hex chars.
- [ ] **Step 4: Run** same command → PASS.
- [ ] **Step 5: Commit** `feat: models and JSON persistence core`.

### Task 3: PrfGenerator

**Files:**
- Create: `app/src/main/kotlin/io/unisondroid/app/sync/PrfGenerator.kt`
- Test: `app/src/test/kotlin/io/unisondroid/app/sync/PrfGeneratorTest.kt`

**Interfaces:**
- Consumes: `Profile` (Task 2).
- Produces: `object PrfGenerator { fun generate(profile: Profile, localSocketPort: Int): String }` — Task 8 writes the return value to `<unisonDir>/<profile.id>.prf`.

- [ ] **Step 1: Failing tests** asserting the generated text, verbatim shape:
  - roots first: `root = <localRoot>` then `root = socket://127.0.0.1:<localSocketPort>`
  - defaults block contains `perms = 0`, `links = false`, `fat = true`
  - each ignore pattern emits `ignore = Path <pattern>`
  - advanced block appended verbatim after everything
  - localRoot `/storage/emulated/0/My Folder/中文` appears byte-identical (no quoting/escaping)
  - empty ignores/advanced produce no blank residue beyond standard newlines.
- [ ] **Step 2: Run** `gradle-test "io.unisondroid.app.sync.PrfGeneratorTest"` → FAIL.
- [ ] **Step 3: Implement** with a plain StringBuilder; no escaping logic anywhere.
- [ ] **Step 4: Run** → PASS. **Step 5: Commit** `feat: unison profile generator`.

### Task 4: OutputParser

**Files:**
- Create: `app/src/main/kotlin/io/unisondroid/app/sync/OutputParser.kt`
- Test: `app/src/test/kotlin/io/unisondroid/app/sync/OutputParserTest.kt`

**Interfaces:**
- Produces:
  - `sealed interface SyncEvent { data class Progress(fraction: Float, label: String); data class Conflict(path: String); data class FailedItem(path: String, message: String); data class VersionMismatch(detail: String); data object Completed; data class Fatal(message: String) }`
  - `data class SyncSummary(transferred: Int, failed: Int, conflicts: Int)`
  - `class OutputParser { fun feed(line: String): List<SyncEvent>; fun finalize(exitCode: Int): SyncSummary }`

- [ ] **Step 1: Failing tests** using representative 2.53 output fixtures (commit them as resources under `app/src/test/resources/unison-output/`): progress lines `[wnt] ...  12/345 KiB  filename`, `Synchronization complete ... k files ... b KiB transferred` → `Completed`, conflict lines containing `conflict`/`[CONFLICT]`, failed items `[FAILED]...`/`Error:`, banner lines containing `different versions` or `incompatible` → `VersionMismatch`. Feed tests: one logical line split across `feed("\r")` fragments is reassembled; long line (>4 KiB) doesn't drop trailing events; counts in `finalize` match fixture totals; nonzero exit without `Completed` → parser appends nothing extra (engine maps exit codes in Task 8).
- [ ] **Step 2: Run** `gradle-test "io.unisondroid.app.sync.OutputParserTest"` → FAIL.
- [ ] **Step 3: Implement.** Before encoding the version-mismatch matcher, `git clone --depth 1 --branch <UNISON_VERSION> https://github.com/bcpierce003/unison` and grep `setup.ml`/`*.ml` for the exact mismatch message; encode the real strings (keep the loose fixtures as defense). Record the tag in `native/build-unison.sh` now (`UNISON_VERSION=<tag>`).
- [ ] **Step 4: Run** → PASS. **Step 5: Commit** `feat: unison output parser with fixtures`.

### Task 5: SshTunnel + HostKeyStore (TOFU)

**Files:**
- Create: `app/src/main/kotlin/io/unisondroid/app/sync/SshTunnel.kt`, `data/HostKeyStore.kt`
- Test: `app/src/test/kotlin/io/unisondroid/app/sync/SshTunnelTest.kt`, `data/HostKeyStoreTest.kt`

**Interfaces:**
- Consumes: `KnownHost` (Task 2), `JsonStore` (Task 2).
- Produces:
  - `data class TunnelSpec(host: String, port: Int, user: String, privateKeyPem: String, remoteSocketPort: Int)`
  - `fun interface HostKeyDecision { suspend fun decide(fingerprint: String): Boolean }` — return false to abort.
  - `interface SshTunnel { suspend fun open(spec: TunnelSpec, decision: HostKeyDecision): TunnelHandle }`
  - `interface TunnelHandle { val localPort: Int; fun close(); suspend fun awaitClosed() }`
  - `class SshjTunnel : SshTunnel` (sshj connect + `newLocalPortForwarder` to `127.0.0.1:remoteSocketPort`, ephemeral local port; calls `decision` with the host key fingerprint **before** auth; aborts on `false`).
  - `class HostKeyStore(private val store: JsonStore)`: `suspend fun known(host: String, port: Int): KnownHost?`, `suspend fun approve(host: String, port: Int, fingerprint: String)`, and `suspend fun verify(host: String, port: Int, fingerprint: String): TofuVerdict` where `enum TofuVerdict { APPROVED, UNKNOWN, CHANGED }`.

- [ ] **Step 1: Failing tests.** `HostKeyStoreTest`: unknown host → `UNKNOWN`; approve → `APPROVED`; different fingerprint after approval → `CHANGED`; persistence across store reload. `SshTunnelTest` (JVM, embedded Apache MINA sshd server on localhost with publickey auth and tcpip-forward enabled): `open forwards bytes from localPort to a server-side echo socket`; `open rejects unknown host key when decision returns false`; `open succeeds when decision approves (TOFU first use)`; `close tears down localPort`. Generate the test Ed25519 pair with sshj itself.
- [ ] **Step 2: Run** `gradle-test "io.unisondroid.app.sync.SshTunnelTest" "io.unisondroid.app.data.HostKeyStoreTest"` → FAIL.
- [ ] **Step 3: Implement.** sshj fingerprint: format host key as OpenSSH-style SHA256 base64 (no colons) fingerprint string. If MINA needs an explicit `DefaultForwardingFilter` to allow tcpip-forward, configure it in the test server only.
- [ ] **Step 4: Run** → PASS. **Step 5: Commit** `feat: ssh tunnel with TOFU host-key verification`.

### Task 6: KeyVault

**Files:**
- Create: `app/src/main/kotlin/io/unisondroid/app/data/KeyVault.kt`
- Test: `app/src/test/kotlin/io/unisondroid/app/data/KeyVaultTest.kt`

**Interfaces:**
- Consumes: `SshKey`, `JsonStore` (Task 2).
- Produces:
  - `interface KeyCipher { fun encrypt(plain: ByteArray): ByteArray; fun decrypt(blob: ByteArray): ByteArray }` (production `KeystoreAesCipher` in same file: AES-256-GCM key held in the Android Keystore, alias `unisondroid-master`; random 12-byte IV prepended).
  - `class KeyVault(private val store: JsonStore, private val cipher: KeyCipher)`: `suspend fun generate(name: String): SshKey` (Ed25519 via sshj, OpenSSH-format private key PEM encrypted through `cipher`, publicKey in `ssh-ed25519 AAAA... comment` one-liner); `suspend fun importOpenSsh(name: String, pem: String): SshKey` (validates by loading through sshj's `KeyProvider`; throws `KeyVaultException` on malformed); `suspend fun keys(): List<SshKey>`; `suspend fun privateKeyPem(id: String): String`.

- [ ] **Step 1: Failing tests** with a fake in-memory cipher: `generate returns loadable key pair` (decrypt + sshj-load succeeds, public line starts `ssh-ed25519`); `round-trip via privateKeyPem matches`; `import of garbage throws KeyVaultException`; `import of valid OpenSSH ed25519 key succeeds`; `keys lists saved keys`.
- [ ] **Step 2: Run** `gradle-test "io.unisondroid.app.data.KeyVaultTest"` → FAIL.
- [ ] **Step 3: Implement** (KeystoreAesCipher is thin; it is exercised on-device in Task 15).
- [ ] **Step 4: Run** → PASS. **Step 5: Commit** `feat: ssh key vault with keystore encryption`.

### Task 7: UnisonRunner + BinaryLocator

**Files:**
- Create: `app/src/main/kotlin/io/unisondroid/app/sync/UnisonRunner.kt`
- Test: `app/src/test/kotlin/io/unisondroid/app/sync/UnisonRunnerTest.kt`

**Interfaces:**
- Produces:
  - `class BinaryLocator(private val nativeLibraryDir: File) { fun locate(): BinaryStatus }` with `sealed interface BinaryStatus { data class Available(path: File); data object Missing }`.
  - `class UnisonRunner(private val binary: File) { fun start(env: Map<String, String>, args: List<String>): RunningProcess }`
  - `class RunningProcess(val output: Flow<String>, private val handle: ProcessHandle...): suspend fun exitCode(): Int; fun kill()` — output is line-split lines from stdout+stderr merged, on `Dispatchers.IO`.

- [ ] **Step 1: Failing tests.** UnisonRunner tests use a fake "unison": helper shell script written to `@TempDir` (chmod +x). Cases: `args and env reach the binary intact` (script dumps `--args`/env as JSON to stdout; assert exact strings incl. `"My Folder/中文"` and `UNISON=/x/unison`); `output flow emits lines until exit`; `kill produces nonzero exitCode`; `nonexistent binary throws IOException at start`. BinaryLocator: temp dir with `libunison.so` → `Available`; without → `Missing`.
- [ ] **Step 2: Run** `gradle-test "io.unisondroid.app.sync.UnisonRunnerTest"` → FAIL.
- [ ] **Step 3: Implement** with `ProcessBuilder` (argv list, never a shell string); merge stderr; destroy process group on kill (`handle.destroyForcibly()`).
- [ ] **Step 4: Run** → PASS. **Step 5: Commit** `feat: unison process runner and binary locator`.

### Task 8: SyncEngine

**Files:**
- Create: `app/src/main/kotlin/io/unisondroid/app/sync/SyncEngine.kt`
- Test: `app/src/test/kotlin/io/unisondroid/app/sync/SyncEngineTest.kt`

**Interfaces:**
- Consumes: Tasks 2–7 (`ProfileRepository`, `PrfGenerator`, `SshTunnel`/`TunnelHandle`, `KeyVault`, `UnisonRunner`/`RunningProcess`, `BinaryLocator`, `OutputParser`, `HostKeyStore`).
- Produces:
  - `sealed interface SyncState { data object Idle; data class AwaitingHostKey(profileId: String, fingerprint: String); data class Connecting(profileId: String); data class Syncing(profileId: String, log: List<String>, progress: Float); data class Finished(profileId: String, summary: SyncSummary); data class Failed(profileId: String, reason: Reason, detail: String); data object Busy }` with `enum Reason { BINARY_MISSING, AUTH, TUNNEL, VERSION, LOCAL_PERMISSIONS, CANCELLED, EXIT, UNKNOWN }` and `log` capped at the last **2000** lines.
  - `class SyncEngine(deps...)` exposing `val state: StateFlow<SyncState>`, `suspend fun requestSync(profileId: String)`, `fun cancel()`. Constructor takes interfaces — tests inject fakes; also inject `unisonDir: File` and a `Clock`.

**Sync procedure (the exact order Task 8 implements):** locate binary (`Missing` → `Failed(BINARY_MISSING)`) → guard one-at-a-time (`Busy` if already syncing) → load profile → build `TunnelSpec` with decrypted key → `HostKeyStore.verify`: `UNKNOWN` → emit `AwaitingHostKey`, await a `decision` (exposed as `suspend fun respondHostKey(approve: Boolean)` on the engine); `CHANGED` → `Failed(TUNNEL)` → open tunnel → write `<unisonDir>/<id>.prf` → start `UnisonRunner` with `env=UNISON→unisonDir`, args `["<id>", "-batch"]` → feed lines through `OutputParser` into state/log/progress → collect exit; map: auth failure → `AUTH`, tunnel refused/closed → `TUNNEL`, `VersionMismatch` → `VERSION`, exit 0 + no failures → `Finished`, nonzero → `Failed(EXIT)`; always close tunnel and kill process on any path including exceptions and cancel (`CANCELLED`). Update `Profile.lastSyncedAt/lastResult`.

- [ ] **Step 1: Failing tests** (all with fakes): `happy path connects-writes-runs-finishes in order` (fake runner emits two fixture lines); `unknown host key pauses for decision and approves`; `host key decision false fails with AUTH and closes tunnel`; `changed host key fails TUNNEL`; `second request while syncing returns Busy`; `cancel kills process closes tunnel and reports CANCELLED`; `tunnel awaitClosed mid-sync kills process → Failed(TUNNEL)`; `binary missing → Failed(BINARY_MISSING) before any IO`; `exit 3 maps to Failed(EXIT) with detail tail`; `parser VersionMismatch event → Failed(VERSION)`; `output containing "Permission denied" → Failed(LOCAL_PERMISSIONS)`; `log buffer caps at 2000 lines`; `profile lastResult updated to OK/WARNINGS/FAILED per summary`. Verify the prf file content on disk in the happy-path test (roots + defaults present).
- [ ] **Step 2: Run** `gradle-test "io.unisondroid.app.sync.SyncEngineTest"` → FAIL.
- [ ] **Step 3: Implement** as a serialized coroutine (`Mutex`/single worker scope); no component touched outside the constructor-injected interfaces.
- [ ] **Step 4: Run** → PASS. **Step 5: Commit** `feat: sync engine orchestration`.

### Task 9: SyncService (foreground)

**Files:**
- Modify: `app/src/main/AndroidManifest.xml` (add `<service android:name=".service.SyncService" android:foregroundServiceType="dataSync" android:exported="false"/>`, permissions already in Task 1)
- Create: `app/src/main/kotlin/io/unisondroid/app/service/SyncService.kt`
- Test: `app/src/test/kotlin/io/unisondroid/app/service/SyncServiceTest.kt` (Robolectric)

**Interfaces:**
- Consumes: `SyncEngine` (Task 8) — the service is its Android host; built via a simple `ServiceLocator` object in the same file.
- Produces: `class SyncService : Service` — `start(profileId)` via `ContentUris`-style static intent helper `SyncService.intent(context, profileId)`; notification channel `unison_sync`; notifications for Running (ongoing) / Finished / Failed; `POST_NOTIFICATIONS` requested on API 33+ at first run-screen entry (Task 12 calls the launcher; service must not crash when permission denied).

- [ ] **Step 1: Failing Robolectric tests**: `startForeground is called with a dataSync-type notification on sync start`; `engine state transitions update the notification text`; `stopSelf when engine returns to Idle/Finished/Failed`.
- [ ] **Step 2: Run** `gradle-test "io.unisondroid.app.service.SyncServiceTest"` → FAIL.
- [ ] **Step 3: Implement** (bind not needed; service collects `SyncEngine.state` in `lifecycleScope`).
- [ ] **Step 4: Run** → PASS. **Step 5: Commit** `feat: foreground sync service`.

### Task 10: UI shell + Profiles screen

**Files:**
- Create: `app/src/main/kotlin/io/unisondroid/app/ui/Navigation.kt`, `ui/ProfilesScreen.kt`, `ui/components/StatusChip.kt`
- Modify: `MainActivity.kt` (NavHost + run as SyncService client)
- Test: `app/src/test/kotlin/io/unisondroid/app/ui/ProfilesScreenTest.kt` (Robolectric + compose-ui-test)

**Interfaces:**
- Consumes: `ProfileRepository`, `SyncEngine.state` (via `ServiceLocator`).
- Produces: `ProfilesScreen(onOpenProfile: (String?) -> Unit, onStartSync: (String) -> Unit)`; nav routes `"profiles"`, `"editor/{id}"`, `"run/{id}"`, `"keys"`, `"about"`.

- [ ] **Step 1: Failing test**: empty repo renders the getting-started card (link text to `docs/SERVER-SETUP.md` content mirrored as in-app text); seeded profiles render name + status chip + last-sync time; tapping a profile calls `onStartSync` with its id and navigates to run.
- [ ] **Step 2: Run** `gradle-test "io.unisondroid.app.ui.ProfilesScreenTest"` → FAIL.
- [ ] **Step 3: Implement** (Material 3 `ListItem`s, `StatusChip` mapping `SyncResult` → color/label).
- [ ] **Step 4: Run** → PASS. **Step 5: Commit** `feat: profiles list and navigation shell`.

### Task 11: Profile editor + path browser

**Files:**
- Create: `app/src/main/kotlin/io/unisondroid/app/ui/ProfileEditorScreen.kt`, `ui/PathBrowser.kt`
- Test: `app/src/test/kotlin/io/unisondroid/app/ui/ProfileEditorScreenTest.kt`

**Interfaces:**
- Consumes: `ProfileRepository` (+ `KeyVault.keys()` for key picker).
- Produces: `ProfileEditorScreen(profileId: String?, onSaved: () -> Unit)`; `PathBrowser(startDir: File, onPicked: (String) -> Unit)` walking `java.io.File` under `/storage/emulated/0` (create-dir button included).

- [ ] **Step 1: Failing test**: new-profile form validates required fields (name, localRoot, host, user, remoteRoot) and rejects save otherwise; `PathBrowser` lists temp-dir children and returns the picked absolute path; advanced section text round-trips into the saved profile; saved profile persists via repository (fake).
- [ ] **Step 2: Run** `gradle-test "io.unisondroid.app.ui.ProfileEditorScreenTest"` → FAIL.
- [ ] **Step 3: Implement**; ignore patterns entered one-per-line in a text field, split on save.
- [ ] **Step 4: Run** → PASS. **Step 5: Commit** `feat: profile editor and path browser`.

### Task 12: Run screen

**Files:**
- Create: `app/src/main/kotlin/io/unisondroid/app/ui/RunScreen.kt`
- Test: `app/src/test/kotlin/io/unisondroid/app/ui/RunScreenTest.kt`

**Interfaces:**
- Consumes: `SyncEngine.state`, `SyncService.intent`, `respondHostKey` flow (engine exposed via ServiceLocator).
- Produces: `RunScreen(profileId: String)` — starts the service on entry, requests notification permission on API 33+, renders live log (reverse layout), progress, cancel button, summary card, `Failed(BINARY_MISSING)` renders an explanatory "engine missing" card.

- [ ] **Step 1: Failing test**: states Idle→Syncing(log lines appear)→Finished(summary) render each; `AwaitingHostKey` shows fingerprint dialog with Approve/Deny calling the engine; cancel button visible while Syncing; `Failed(BINARY_MISSING)` shows the engine-missing card.
- [ ] **Step 2: Run** `gradle-test "io.unisondroid.app.ui.RunScreenTest"` → FAIL.
- [ ] **Step 3: Implement.** **Step 4: Run** → PASS. **Step 5: Commit** `feat: run screen with live log and host-key prompt`.

### Task 13: Keys screen + About

**Files:**
- Create: `app/src/main/kotlin/io/unisondroid/app/ui/KeysScreen.kt`, `ui/AboutScreen.kt`, `app/src/main/kotlin/io/unisondroid/app/sync/UnisonInfo.kt`
- Modify: `docs/SERVER-SETUP.md` (full content: authorized_keys walkthrough + systemd user unit `unison-socket.service` for `unison -socket 22333`)
- Test: `app/src/test/kotlin/io/unisondroid/app/ui/KeysScreenTest.kt`

**Interfaces:**
- Consumes: `KeyVault`, `BinaryLocator`.
- Produces: `KeysScreen()` (list, Generate with name prompt, Import from pasted PEM, copy/share public key via clipboard + `Intent.ACTION_SEND`); `AboutScreen()` showing the embedded unison version from `object UnisonInfo { const val UNISON_VERSION: String = "dev" }` (created here; Task 14 updates it to the real pinned version), license text, server-setup steps mirrored from the doc.

- [ ] **Step 1: Failing test**: Generate calls vault and lists the new key with a `ssh-ed25519` public line; copy places the public key on the clipboard; Import with invalid PEM shows an error snackbar and does not add a key.
- [ ] **Step 2: Run** `gradle-test "io.unisondroid.app.ui.KeysScreenTest"` → FAIL.
- [ ] **Step 3: Implement.** **Step 4: Run** → PASS. **Step 5: Commit** `feat: key management and about screens`.

### Task 14: Native unison cross-compile pipeline

**Files:**
- Create: `native/build-unison.sh`, `native/README.md`, `.github/actions` note none — CI wiring is Task 16
- Test: manual/CI verification (see steps); no JVM tests in this task

**Interfaces:**
- Consumes: `UNISON_VERSION` recorded in Task 4.
- Produces: `native/out/<abi>/libunison.so` for `arm64-v8a` and `x86_64`; `app/src/main/jniLibs/<abi>/libunison.so` copied in (jniLibs gitignored); update of `sync/UnisonInfo.kt` `UNISON_VERSION` to the real pinned value.

- [ ] **Step 1: Write `native/build-unison.sh <abi>`**: install pinned NDK (revision recorded at top of script); download+build OCaml 4.14.x with `--host aarch64-linux-android`/`x86_64-linux-android` using NDK clang as `CC` (host bytecode compiler built first — adapt the approach used by Termux's `unison`/`ocaml` package recipes; expect iteration here, it is the plan's riskiest step); then build unison `${UNISON_VERSION}` `NATIVE=true` statically against bionic; strip; copy result as `native/out/<abi>/libunison.so`. `native/README.md` documents prerequisites (Linux host, ~4 GiB RAM) and the copy into jniLibs.
- [ ] **Step 2: Verify locally**: run for both ABIs; `file native/out/*/libunison.so` reports `aarch64`/`x86-64` ELF (not a script, not dynamically linked to glibc); `ls -la` < 20 MiB each.
- [ ] **Step 3: Verify on device**: with an emulator (x86_64 image), `adb push native/out/x86_64/libunison.so /data/local/tmp/unison && adb shell "chmod +x /data/local/tmp/unison && /data/local/tmp/unison -version"` prints `unison version ${UNISON_VERSION}`. Then copy both into jniLibs, install the app, and confirm `BinaryLocator.locate()` returns `Available` (About screen shows the version).
- [ ] **Step 4: Add `.gitignore` entries for `native/out/`, `app/src/main/jniLibs/`**; commit `build: cross-compile unison for arm64-v8a and x86_64`.

### Task 15: Instrumented end-to-end (local↔local)

**Files:**
- Test: `app/src/androidTest/kotlin/io/unisondroid/app/LocalSyncE2eTest.kt`, `androidTest/.../KeystoreAesCipherTest.kt`

**Interfaces:**
- Consumes: everything; runs on an x86_64 emulator with the embedded binary present (Task 14).

- [ ] **Step 1: Write tests**: `LocalSyncE2eTest` — exec `libunison.so` directly with two temp roots under `context.cacheDir` (no ssh): create/modify/delete files on one side, sync with `-batch`, assert mirrored state both directions and idempotence on re-run; also assert `fat`-style mtime tolerance (set mtime with 1s skew, re-sync transfers nothing). `KeystoreAesCipherTest` — encrypt/decrypt round-trip on-device; `KeyVault` generate → `privateKeyPem` round-trip on-device.
- [ ] **Step 2: Run** `gradle-it` → PASS (fix app code where the e2e exposes real breaks; this is the task's purpose).
- [ ] **Step 3: Commit** `test: instrumented local sync e2e`.

### Task 16: CI + release plumbing

**Files:**
- Create: `.github/workflows/ci.yml`, `.github/workflows/release.yml`, `fastlane/metadata/android/en-US/` (title, summary, description, changelogs), `docs/RELEASE.md`
- Modify: `README.md` (build-from-source, server setup pointer)

**Interfaces:**
- Consumes: Tasks 14's script, Gradle build.

- [ ] **Step 1: `ci.yml`** on PR/push: job `native` runs `native/build-unison.sh` for both ABIs (cache NDK+OCaml by revision), uploads jniLibs artifact; job `jvm` runs `gradle-test` (no binary needed); job `app` downloads artifacts → `assembleDebug` → `lint`; job `e2e` runs `gradle-it` on the x86_64 emulator.
- [ ] **Step 2: `release.yml`** on tag `v*`: same pipeline → sign (secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) → GitHub Release with APKs. `docs/RELEASE.md` documents secret setup and the F-Droid inclusion MR checklist; fastlane metadata filled from the spec's user-view copy.
- [ ] **Step 3: Verify**: push a branch, watch all jobs green; tag a `v0.0.1-test` on the branch, confirm the release artifact installs on an emulator.
- [ ] **Step 4: Commit** `build: ci, release workflow, and f-droid metadata`.

---

## Out of scope (do not build)

Auto-sync/scheduling, conflict-resolution UI, plain socket transport, armeabi-v7a, Play Store, SAF-based picking.
