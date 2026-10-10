# Multi-Version Unison Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bundle Unison 2.54.0 and 2.53.8 as separate native binaries and let each profile select which one to run, defaulting to 2.54.0.

**Architecture:** A single source of truth (`native/unison-versions.txt`) drives both the native build and a Kotlin registry (`UnisonInfo`). `Profile` carries a version string; `SyncEngine` resolves it to a packaged `libunison_<ver>.so` via `BinaryLocator`. The profile editor, About screen, and the run-screen mismatch flow expose selection.

**Tech Stack:** Kotlin 2.x, Jetpack Compose (M3), kotlinx-serialization, JUnit 5 + Robolectric (JVM tests), Android instrumentation tests, Bash + NDK/OCaml cross-compile, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-10-10-multi-unison-versions-design.md`

## Global Constraints

- Bundled versions: **2.54.0** (default) and **2.53.8**.
- Packaged file name rule: `libunison_` + version with `.` → `_` + `.so` (e.g. `2.54.0` → `libunison_2_54_0.so`). Every name starts with `lib` and ends with `.so`.
- Single source of truth: `native/unison-versions.txt`, one `v`-prefixed release tag per line, newest first; blank/`#` lines ignored.
- `Profile.unisonVersion` default is `""`, meaning "use the default version".
- Do **not** add Gradle codegen, ABI splits, or download-on-demand.
- Keep `useLegacyPackaging = true` in `app/build.gradle.kts`; keep `minSdk 26` / `targetSdk 36`.
- `OutputParser` stays tuned to 2.53.8; do not add per-version parser branches.

## Review Focus

- A profile whose `unisonVersion` is blank, absent from old JSON, or names a version no longer bundled must run the default binary and never crash. (Task 2)
- A partially installed app (only one `libunison_*.so` present) must fail with `BINARY_MISSING` naming the exact file, not crash or run the wrong version. (Task 2)
- A version-mismatch failure must offer a retry that actually rewrites the profile and re-runs — not just a message. (Task 5)
- The native build must produce the exact expected file name for every ABI × version, and CI must fail loudly if one is missing. (Task 6, Task 7)
- Every bundled binary must actually execute on-device and produce parseable output, not merely exist. (Task 8)

---

### Task 1: Version registry and source-of-truth file

**Files:**
- Create: `native/unison-versions.txt`
- Create: `app/src/main/kotlin/io/unisondroid/app/sync/UnisonInfo.kt` (replace contents)
- Test: `app/src/test/kotlin/io/unisondroid/app/sync/UnisonInfoTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `data class BundledUnison(val version: String, val fileName: String)` in package `io.unisondroid.app.sync`.
  - `object UnisonInfo { val BUNDLED: List<BundledUnison>; val DEFAULT: BundledUnison; fun forVersion(version: String): BundledUnison }`.

- [ ] **Step 1: Create the source-of-truth file**

`native/unison-versions.txt`:
```
# Unison release tags bundled into the app, newest first.
# Consumed by native/build-unison.sh and CI; asserted against UnisonInfo.BUNDLED.
v2.54.0
v2.53.8
```

- [ ] **Step 2: Write the failing tests**

`UnisonInfoTest.kt`:
```kotlin
package io.unisondroid.app.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class UnisonInfoTest {

    @Test
    fun `bundled versions match native-unison-versions file`() {
        val tags = File(repoRoot(), "native/unison-versions.txt").readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.removePrefix("v") }
        assertEquals(tags.toSet(), UnisonInfo.BUNDLED.map { it.version }.toSet())
    }

    @Test
    fun `file names follow the encoding rule`() {
        UnisonInfo.BUNDLED.forEach { b ->
            assertEquals("libunison_${b.version.replace('.', '_')}.so", b.fileName)
        }
    }

    @Test
    fun `forVersion falls back to default for unknown or blank`() {
        assertEquals(UnisonInfo.DEFAULT, UnisonInfo.forVersion(""))
        assertEquals(UnisonInfo.DEFAULT, UnisonInfo.forVersion("1.2.3"))
        assertEquals("2.53.8", UnisonInfo.forVersion("2.53.8").version)
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, "native/unison-versions.txt").isFile) {
            dir = dir.parentFile ?: error("native/unison-versions.txt not found from ${System.getProperty("user.dir")}")
        }
        return dir
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*UnisonInfoTest'`
Expected: FAIL to compile (`UnisonInfo.forVersion` unresolved).

- [ ] **Step 4: Implement `UnisonInfo`**

Replace `app/src/main/kotlin/io/unisondroid/app/sync/UnisonInfo.kt`:
```kotlin
package io.unisondroid.app.sync

data class BundledUnison(val version: String, val fileName: String)

object UnisonInfo {
    val BUNDLED: List<BundledUnison> = listOf(
        BundledUnison("2.54.0", "libunison_2_54_0.so"),
        BundledUnison("2.53.8", "libunison_2_53_8.so"),
    )
    val DEFAULT: BundledUnison = BUNDLED.first()

    fun forVersion(version: String): BundledUnison =
        BUNDLED.firstOrNull { it.version == version } ?: DEFAULT
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*UnisonInfoTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add native/unison-versions.txt \
  app/src/main/kotlin/io/unisondroid/app/sync/UnisonInfo.kt \
  app/src/test/kotlin/io/unisondroid/app/sync/UnisonInfoTest.kt
git commit -m "feat: bundled Unison version registry and source-of-truth file"
```

---

### Task 2: Version-aware binary resolution

**Files:**
- Modify: `app/src/main/kotlin/io/unisondroid/app/data/Models.kt` (`Profile`)
- Modify: `app/src/main/kotlin/io/unisondroid/app/sync/UnisonRunner.kt` (`BinaryLocator`)
- Modify: `app/src/main/kotlin/io/unisondroid/app/sync/SyncEngine.kt` (`locateBinaries`)
- Modify: `app/src/test/kotlin/io/unisondroid/app/sync/UnisonRunnerTest.kt`
- Modify: `app/src/test/kotlin/io/unisondroid/app/sync/SyncEngineTest.kt`
- Modify: `app/src/test/kotlin/io/unisondroid/app/data/ProfileRepositoryTest.kt`
- Modify: `app/src/androidTest/kotlin/io/unisondroid/app/LocalSyncE2eTest.kt`

**Interfaces:**
- Consumes: `UnisonInfo`, `BundledUnison` (Task 1).
- Produces:
  - `Profile.unisonVersion: String = ""`.
  - `BinaryLocator.locate(fileName: String): BinaryStatus`.
  - `SyncEngine.locateBinaries(profileId: String): Pair<File, File>?` is now `suspend`.

- [ ] **Step 1: Write the failing tests**

In `UnisonRunnerTest.kt`, replace the two `locate` tests with:
```kotlin
    @Test
    fun `locate returns Available pointing at the named file when present`() {
        val nativeDir = File(tempDir, "native").apply { mkdirs() }
        val lib = File(nativeDir, "libunison_2_53_8.so").apply { writeText("fake-so") }

        assertEquals(BinaryStatus.Available(lib), BinaryLocator(nativeDir).locate("libunison_2_53_8.so"))
    }

    @Test
    fun `locate returns Missing when the named file is absent`() {
        val nativeDir = File(tempDir, "native").apply { mkdirs() }

        assertEquals(BinaryStatus.Missing, BinaryLocator(nativeDir).locate("libunison_2_54_0.so"))
    }
```

In `SyncEngineTest.kt`, add a `unisonVersion` parameter to `harness`, replace the single-file writes, and add the selection tests:
```kotlin
    private suspend fun harness(
        scripts: List<ScriptedProcess>,
        profileIds: List<String> = listOf("prof1", "prof2"),
        unisonVersion: String = "",
        availableVersions: Set<String> = UnisonInfo.BUNDLED.map { it.version }.toSet(),
        hostIsKnown: Boolean = true,
        sshBinaryAvailable: Boolean = true,
        // ... remaining params unchanged ...
    ): Harness {
        UnisonInfo.BUNDLED.filter { it.version in availableVersions }
            .forEach { File(nativeDir, it.fileName).writeText("fake-binary") }
        if (sshBinaryAvailable) File(nativeDir, "libssh.so").writeText("fake-ssh")
        // ...
        val profiles = profileIds.associateWith { profile(it, key.id, unisonVersion) }
        // ...
        val runner = FakeRunner(scripts, events, onStart)
        val startedBinaries = mutableListOf<File>()
        val engine = SyncEngine(
            binaryLocator = BinaryLocator(nativeDir),
            // ...
            runnerFactory = { binary -> startedBinaries += binary; runner },
            // ...
        )
        return Harness(engine, repo, vault, sshTool, runner, events, profiles, key.id, startedBinaries)
    }

    private fun profile(id: String, sshKeyId: String, unisonVersion: String = "") = Profile(
        id = id,
        // ... unchanged ...
        sshKeyId = sshKeyId,
        unisonVersion = unisonVersion,
    )
```
Add `startedBinaries: MutableList<File>` to `Harness`. Update the two existing failure tests:
- Line ~279: `File(nativeDir, "libunison.so").delete()` → `File(nativeDir, UnisonInfo.DEFAULT.fileName).delete()`.
- Line ~311: `binaryAvailable = false` → `availableVersions = emptySet()`.
- Line ~319: `assertTrue(failed.detail.contains("libunison.so"))` → `assertTrue(failed.detail.contains(UnisonInfo.DEFAULT.fileName), "detail was: ${failed.detail}")`.

New tests:
```kotlin
    @Test
    fun `profile version selects the matching bundled binary`() = runTest {
        val h = harness(
            scripts = listOf(ScriptedProcess(lines = listOf(SUMMARY_LINE))),
            unisonVersion = "2.53.8",
        )
        withTimeout(10_000) { h.engine.requestSync("prof1", SyncMode.INTERACTIVE) }
        assertEquals(File(nativeDir, "libunison_2_53_8.so"), h.startedBinaries.single())
    }

    @Test
    fun `blank version runs the default binary`() = runTest {
        val h = harness(scripts = listOf(ScriptedProcess(lines = listOf(SUMMARY_LINE))))
        withTimeout(10_000) { h.engine.requestSync("prof1", SyncMode.INTERACTIVE) }
        assertEquals(File(nativeDir, UnisonInfo.DEFAULT.fileName), h.startedBinaries.single())
    }

    @Test
    fun `unknown version falls back to the default binary`() = runTest {
        val h = harness(
            scripts = listOf(ScriptedProcess(lines = listOf(SUMMARY_LINE))),
            unisonVersion = "9.9.9",
        )
        withTimeout(10_000) { h.engine.requestSync("prof1", SyncMode.INTERACTIVE) }
        assertEquals(File(nativeDir, UnisonInfo.DEFAULT.fileName), h.startedBinaries.single())
    }

    @Test
    fun `selected binary missing names that exact file`() = runTest {
        val h = harness(
            scripts = listOf(ScriptedProcess(lines = listOf(SUMMARY_LINE))),
            unisonVersion = "2.53.8",
            availableVersions = setOf("2.54.0"),
        )
        val outcome = withTimeout(10_000) { h.engine.requestSync("prof1", SyncMode.INTERACTIVE) }
        val failed = h.engine.state.value as SyncState.Failed
        assertEquals(SyncOutcome.FAILED, outcome)
        assertEquals(SyncState.Reason.BINARY_MISSING, failed.reason)
        assertTrue(failed.detail.contains("libunison_2_53_8.so"), "detail was: ${failed.detail}")
        assertTrue(h.runner.starts.isEmpty())
    }
```
Update `FAKE_BINARY = File("/nowhere/libunison.so")` → `File("/nowhere/libunison_2_54_0.so")`.

In `ProfileRepositoryTest.kt`, extend the existing legacy-JSON test:
```kotlin
    @Test
    fun `profiles saved before the version field existed load as blank`(@TempDir dir: File) = runTest {
        File(dir, "profiles.json").writeText(
            """[{"id":"p1","name":"Phone","localRoot":"/l","remoteRoot":"/r","host":"h","user":"u","sshKeyId":"k"}]""",
        )

        val loaded = ProfileRepository(JsonStore(dir)).profiles().single()

        assertEquals("", loaded.unisonVersion)
    }
```

In `LocalSyncE2eTest.kt`, change `locateBinary()`:
```kotlin
    private fun locateBinary(): File {
        val nativeDir = File(InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo.nativeLibraryDir)
        val status = BinaryLocator(nativeDir).locate(UnisonInfo.DEFAULT.fileName)
        assertTrue("${UnisonInfo.DEFAULT.fileName} must be present in $nativeDir", status is BinaryStatus.Available)
        return (status as BinaryStatus.Available).path
    }
```
Add `import io.unisondroid.app.sync.UnisonInfo`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*SyncEngineTest' --tests '*UnisonRunnerTest'`
Expected: FAIL to compile (`locate` now needs a `fileName` argument; `Profile.unisonVersion` unresolved).

- [ ] **Step 3: Implement**

`Profile` in `Models.kt` — add after `serverCommand`:
```kotlin
    val unisonVersion: String = "",
```

`BinaryLocator` in `UnisonRunner.kt`:
```kotlin
class BinaryLocator(private val nativeLibraryDir: File) {
    fun locate(fileName: String): BinaryStatus {
        val candidate = File(nativeLibraryDir, fileName)
        return if (candidate.isFile) BinaryStatus.Available(candidate) else BinaryStatus.Missing
    }

    fun locateSsh(): BinaryStatus {
        val candidate = File(nativeLibraryDir, "libssh.so")
        return if (candidate.isFile) BinaryStatus.Available(candidate) else BinaryStatus.Missing
    }
}
```

`SyncEngine.locateBinaries` — make it `suspend`, resolve the profile's version, and name the missing file:
```kotlin
    private suspend fun locateBinaries(profileId: String): Pair<File, File>? {
        val bundled = UnisonInfo.forVersion(profiles.get(profileId)?.unisonVersion.orEmpty())
        val binary = when (val status = binaryLocator.locate(bundled.fileName)) {
            is BinaryStatus.Missing -> {
                _state.value = SyncState.Failed(
                    profileId = profileId,
                    reason = SyncState.Reason.BINARY_MISSING,
                    detail = "${bundled.fileName} not found in the native library directory",
                )
                return null
            }
            is BinaryStatus.Available -> status.path
        }
        // ... ssh block unchanged ...
    }
```
No change needed at the call sites in `requestSync` / `resolveConflicts` (they are already `suspend`).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*SyncEngineTest' --tests '*UnisonRunnerTest'`
Expected: PASS.

- [ ] **Step 5: Compile instrumentation tests**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL (proves `LocalSyncE2eTest` compiles against the new `locate`).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/io/unisondroid/app/data/Models.kt \
  app/src/main/kotlin/io/unisondroid/app/sync/UnisonRunner.kt \
  app/src/main/kotlin/io/unisondroid/app/sync/SyncEngine.kt \
  app/src/test/kotlin/io/unisondroid/app/sync/UnisonRunnerTest.kt \
  app/src/test/kotlin/io/unisondroid/app/sync/SyncEngineTest.kt \
  app/src/test/kotlin/io/unisondroid/app/data/ProfileRepositoryTest.kt \
  app/src/androidTest/kotlin/io/unisondroid/app/LocalSyncE2eTest.kt
git commit -m "feat: resolve the profile's bundled Unison binary by version"
```

---

### Task 3: Profile editor version dropdown

**Files:**
- Modify: `app/src/main/kotlin/io/unisondroid/app/ui/ProfileEditorScreen.kt`
- Test: `app/src/test/kotlin/io/unisondroid/app/ui/ProfileEditorScreenTest.kt`

**Interfaces:**
- Consumes: `UnisonInfo.BUNDLED`, `UnisonInfo.forVersion` (Task 1); `Profile.unisonVersion` (Task 2).
- Produces: `const val FIELD_UNISON_VERSION = "editor-unisonVersion"`.

- [ ] **Step 1: Write the failing tests**

Add to `ProfileEditorScreenTest.kt`:
```kotlin
    @Test
    fun `new profiles default to the newest bundled unison version`() {
        compose.setContent { UnisonDroidTheme { ProfileEditorScreen(profileId = null, onSaved = {}) } }
        awaitEditor()
        fillRequired()

        compose.onNodeWithTag(SAVE_BUTTON).performClick()

        assertEquals(UnisonInfo.DEFAULT.version, awaitSavedProfile().unisonVersion)
    }

    @Test
    fun `unison version selection round-trips into the saved profile`() {
        compose.setContent { UnisonDroidTheme { ProfileEditorScreen(profileId = null, onSaved = {}) } }
        awaitEditor()
        fillRequired()

        compose.onNodeWithTag(FIELD_UNISON_VERSION).performScrollTo().performClick()
        compose.onNodeWithText("2.53.8").performClick()
        compose.onNodeWithTag(SAVE_BUTTON).performClick()

        assertEquals("2.53.8", awaitSavedProfile().unisonVersion)
    }
```
Add `import io.unisondroid.app.sync.UnisonInfo`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*ProfileEditorScreenTest'`
Expected: FAIL to compile (`FIELD_UNISON_VERSION` unresolved, `unisonVersion` not set).

- [ ] **Step 3: Implement**

Add the tag constant and an `unisonVersion` state, mirrored on the existing conflict-policy dropdown. In `ProfileEditorContent`:
```kotlin
val FIELD_UNISON_VERSION = "editor-unisonVersion"   // with the other tag constants
```
```kotlin
var unisonVersion by remember(initial) {
    mutableStateOf(UnisonInfo.forVersion(initial?.unisonVersion.orEmpty()).version)
}
var versionMenu by remember { mutableStateOf(false) }
```
Place this UI just below the "Remote unison command" field:
```kotlin
Box {
    OutlinedButton(
        onClick = { versionMenu = true },
        modifier = Modifier.fillMaxWidth().testTag(FIELD_UNISON_VERSION),
    ) { Text(text = "Unison version: $unisonVersion") }
    DropdownMenu(expanded = versionMenu, onDismissRequest = { versionMenu = false }) {
        UnisonInfo.BUNDLED.forEach { bundled ->
            DropdownMenuItem(
                text = { Text(bundled.version) },
                onClick = { unisonVersion = bundled.version; versionMenu = false },
            )
        }
    }
}
```
In `save()`, add `unisonVersion = unisonVersion,` to the constructed `Profile`. Add `import io.unisondroid.app.sync.UnisonInfo`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*ProfileEditorScreenTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/io/unisondroid/app/ui/ProfileEditorScreen.kt \
  app/src/test/kotlin/io/unisondroid/app/ui/ProfileEditorScreenTest.kt
git commit -m "feat: choose the Unison version in the profile editor"
```

---

### Task 4: About screen lists bundled versions

**Files:**
- Modify: `app/src/main/kotlin/io/unisondroid/app/ui/AboutScreen.kt`
- Test: `app/src/test/kotlin/io/unisondroid/app/ui/AboutScreenTest.kt` (new)

**Interfaces:**
- Consumes: `UnisonInfo.BUNDLED` (Task 1).
- Produces: `ABOUT_VERSION_TAG` remains exported; content lists every bundled version.

- [ ] **Step 1: Write the failing test**

`AboutScreenTest.kt`:
```kotlin
package io.unisondroid.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import io.unisondroid.app.sync.UnisonInfo
import io.unisondroid.app.ui.theme.UnisonDroidTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AboutScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `lists every bundled unison version`() {
        compose.setContent { UnisonDroidTheme { AboutScreen() } }

        compose.onNodeWithTag(ABOUT_VERSION_TAG).assertExists()
        UnisonInfo.BUNDLED.forEach { bundled ->
            compose.onNodeWithText(bundled.version, substring = true).assertExists()
            compose.onNodeWithText(bundled.fileName, substring = true).assertExists()
        }
    }
}
```
(Add `import androidx.compose.ui.test.assertIsDisplayed`/`assertExists` as needed — `assertExists` is on `SemanticsNodeInteraction`.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*AboutScreenTest'`
Expected: FAIL — only the single hard-coded version string is shown, so `2.53.8`/`libunison_2_53_8.so` are not found.

- [ ] **Step 3: Implement**

Replace the single version line in `AboutScreen.kt`:
```kotlin
            Text(text = "Bundled Unison versions", style = MaterialTheme.typography.titleMedium)
            UnisonInfo.BUNDLED.forEachIndexed { index, bundled ->
                Text(
                    text = "${bundled.version}  (${bundled.fileName})",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = if (index == 0) Modifier.testTag(ABOUT_VERSION_TAG) else Modifier,
                )
            }
```
Keep `const val ABOUT_VERSION_TAG = "about-version"` and the `UnisonInfo` import.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*AboutScreenTest' --tests '*NavigationTest'`
Expected: PASS (NavigationTest still finds `ABOUT_VERSION_TAG`).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/io/unisondroid/app/ui/AboutScreen.kt \
  app/src/test/kotlin/io/unisondroid/app/ui/AboutScreenTest.kt
git commit -m "feat: list bundled Unison versions on the About screen"
```

---

### Task 5: Run-screen retry with another version

**Files:**
- Modify: `app/src/main/kotlin/io/unisondroid/app/ui/RunScreen.kt`
- Test: `app/src/test/kotlin/io/unisondroid/app/ui/RunScreenTest.kt`

**Interfaces:**
- Consumes: `UnisonInfo.BUNDLED` (Task 1); `ProfileRepository.get/save`; `Profile.copy`.
- Produces:
  - `const val RUN_RETRY_VERSION_TAG = "run-retryVersion"`.
  - `RunContent(..., onRetryWithVersion: (String) -> Unit = {})`.

- [ ] **Step 1: Write the failing tests**

Add to `RunScreenTest.kt`:
```kotlin
    @Test
    fun `version failure offers a retry per bundled version`() {
        var retried: String? = null
        compose.setContent {
            UnisonDroidTheme {
                RunContent(
                    state = SyncState.Failed("p1", SyncState.Reason.VERSION, "different versions of Unison"),
                    onCancel = {},
                    onHostKeyDecision = {},
                    onRetryWithVersion = { retried = it },
                )
            }
        }

        compose.onNodeWithText("Try Unison 2.53.8").performClick()
        assertEquals("2.53.8", retried)
    }

    @Test
    fun `retry rewrites the profile version and starts a new run`() {
        val dir = java.nio.file.Files.createTempDirectory("run-retry-test").toFile()
        val repo = ProfileRepository(JsonStore(dir))
        val profile = Profile(
            id = "p1", name = "P", localRoot = "/a", remoteRoot = "/b",
            host = "h", user = "u", sshKeyId = "k",
        )
        runBlocking { repo.save(profile) }
        ServiceLocator.profilesProvider = { repo }

        compose.setContent { UnisonDroidTheme { RunScreen(profileId = "p1") } }
        engine.states.value = SyncState.Failed("p1", SyncState.Reason.VERSION, "different versions of Unison")
        awaitText("Try Unison 2.53.8")

        compose.onNodeWithText("Try Unison 2.53.8").performClick()

        compose.waitUntil(5_000) { runBlocking { repo.get("p1")?.unisonVersion } == "2.53.8" }
        assertEquals("2.53.8", runBlocking { repo.get("p1")!!.unisonVersion })
    }
```
Add `beginRunCount` to `FakeSyncEngine` (override `beginRun`), and in the retry test assert it advanced:
```kotlin
    // in FakeSyncEngine
    var beginRunCount = 0
        private set
    override fun beginRun() { beginRunCount++ }
```
Add to the retry test: `assertTrue(engine.beginRunCount >= 2, "retry must re-trigger the run")`.
Add imports as needed (`Profile`, `ProfileRepository`, `runBlocking`, `java.nio.file.Files`).
Because the retry test overrides `ServiceLocator.profilesProvider`, add to `RunScreenTest.tearDown()`:
`ServiceLocator.profilesProvider = ServiceLocator.defaultProfilesProvider`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*RunScreenTest'`
Expected: FAIL to compile (`onRetryWithVersion` unresolved).

- [ ] **Step 3: Implement**

In `RunScreen.kt`:
```kotlin
const val RUN_RETRY_VERSION_TAG = "run-retryVersion"
```
`RunScreen` — add a repository, a run key, and the retry action:
```kotlin
    val repository = remember(context) { ServiceLocator.profiles(context) }
    var runKey by remember { mutableStateOf(0) }

    LaunchedEffect(profileId, variant, confirmed, runKey) {
        engine.beginRun()
        SyncScheduler(context).syncNow(profileId, variant, confirmed)
    }
```
Pass to `RunContent`:
```kotlin
        onRetryWithVersion = { version ->
            scope.launch {
                repository.get(profileId)?.let { repository.save(it.copy(unisonVersion = version)) }
                runKey++
            }
        },
```
`RunContent` — add the parameter and thread it to `FailureCard`:
```kotlin
internal fun RunContent(
    state: SyncState,
    onCancel: () -> Unit,
    onHostKeyDecision: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onGrantStorageAccess: () -> Unit = {},
    onResolveConflicts: () -> Unit = {},
    onRetryWithVersion: (String) -> Unit = {},
    variant: SyncVariant = SyncVariant.TWO_WAY,
)
```
In the `SyncState.Failed` branch, pass `onRetryWithVersion` into `FailureCard`; in `FailureCard`, when `state.reason == SyncState.Reason.VERSION`, add:
```kotlin
            if (state.reason == SyncState.Reason.VERSION) {
                Text(
                    text = "The server runs a different Unison version. Try another bundled version:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                UnisonInfo.BUNDLED.forEach { bundled ->
                    OutlinedButton(
                        onClick = { onRetryWithVersion(bundled.version) },
                        modifier = Modifier.testTag(RUN_RETRY_VERSION_TAG),
                    ) { Text(text = "Try Unison ${bundled.version}") }
                }
            }
```
Add `import io.unisondroid.app.sync.UnisonInfo` and `import androidx.compose.runtime.mutableStateOf`/`setValue`/`getValue` as needed.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*RunScreenTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/io/unisondroid/app/ui/RunScreen.kt \
  app/src/test/kotlin/io/unisondroid/app/ui/RunScreenTest.kt
git commit -m "feat: retry a version-mismatch run with another bundled version"
```

---

### Task 6: Parameterize the native build by version

**Files:**
- Modify: `native/build-unison.sh`

**Interfaces:**
- Consumes: `native/unison-versions.txt` (documentation), `UNISON_VERSION` environment variable.
- Produces: `native/out/<abi>/libunison_<ver>.so` and `app/src/main/jniLibs/<abi>/libunison_<ver>.so` for any tag.

- [ ] **Step 1: Implement the parameterization**

In `native/build-unison.sh`:
- Change the pin line to an overridable default:
```bash
UNISON_VERSION="${UNISON_VERSION:-v2.53.8}"
```
- After the pins, derive the packaged file name from the tag:
```bash
UNISON_VER="${UNISON_VERSION#v}"            # v2.54.0 -> 2.54.0
UNISON_LIB="libunison_${UNISON_VER//./_}.so"  # -> libunison_2_54_0.so
```
- Replace the two output paths:
```bash
OUT_BIN="$OUT_DIR/$ABI/$UNISON_LIB"
JNI_BIN="$REPO_ROOT/app/src/main/jniLibs/$ABI/$UNISON_LIB"
```
- Update the header comment (`Produces ... libunison_<version>.so`) and reference `native/unison-versions.txt` for the bundled tags.

- [ ] **Step 2: Verify the name derivation without a full build**

Run:
```bash
bash -c 'for t in v2.54.0 v2.53.8; do v="${t#v}"; echo "libunison_${v//./_}.so"; done'
```
Expected:
```
libunison_2_54_0.so
libunison_2_53_8.so
```

- [ ] **Step 3: Cross-compile each bundled version (requires NDK + OCaml; slow)**

Run:
```bash
UNISON_VERSION=v2.54.0 bash native/build-unison.sh arm64-v8a
UNISON_VERSION=v2.53.8 bash native/build-unison.sh arm64-v8a
ls app/src/main/jniLibs/arm64-v8a/libunison_*.so
```
Expected: both `libunison_2_54_0.so` and `libunison_2_53_8.so` are listed. If 2.54's `lock.ml` O_EXCL patch or `make NATIVE=true src` fails, adjust the script (the patch fails loudly by design). This also runs in CI (Task 7).

- [ ] **Step 4: Commit**

```bash
git add native/build-unison.sh
git commit -m "feat: build and package a named libunison per version"
```

---

### Task 7: CI matrix over bundled versions

**Files:**
- Modify: `.github/workflows/ci.yml`
- Modify: `.github/workflows/release.yml`

**Interfaces:**
- Consumes: `native/build-unison.sh` `UNISON_VERSION` override (Task 6); `native/unison-versions.txt` (Task 1).
- Produces: native jobs keyed by ABI × version; verify step asserting every bundled `.so` exists.

- [ ] **Step 1: Implement the matrix**

In both `ci.yml` and `release.yml`, replace the single `UNISON_VERSION` env with a version matrix and pass it through:
```yaml
    strategy:
      fail-fast: false
      matrix:
        abi: [arm64-v8a, x86_64]
        unison: [v2.54.0, v2.53.8]
```
- Name the job `native (${{ matrix.abi }}, ${{ matrix.unison }})`.
- Change the build step to `UNISON_VERSION: ${{ matrix.unison }}`:
```yaml
      - name: Cross-compile libunison.so (${{ matrix.abi }}, ${{ matrix.unison }})
        env:
          UNISON_VERSION: ${{ matrix.unison }}
        run: bash native/build-unison.sh ${{ matrix.abi }}
```
- Update the cache key to use `${{ matrix.unison }}` and hash `native/unison-versions.txt`:
```yaml
          key: native-${{ runner.os }}-ndk${{ env.NDK_VERSION }}-ocaml${{ env.OCAML_VERSION }}-unison${{ matrix.unison }}-openssh${{ env.OPENSSH_VERSION }}-${{ hashFiles('native/build-unison.sh', 'native/build-openssh.sh', 'native/unison-versions.txt') }}
```
- Update the verify step to check the OpenSSH binaries plus every `libunison_*.so`:
```yaml
      - name: Verify bundled native binaries (${{ matrix.abi }})
        run: |
          set -euo pipefail
          d=app/src/main/jniLibs/${{ matrix.abi }}
          for b in libssh.so libssh-keygen.so libssh-keyscan.so; do
            test -f "$d/$b" || { echo "::error::missing $d/$b"; exit 1; }
          done
          count=$(ls "$d"/libunison_*.so 2>/dev/null | wc -l)
          test "$count" -ge 1 || { echo "::error::no libunison_*.so in $d"; exit 1; }
```
Leave the `release` job's artifact download/assemble steps unchanged (they fetch the whole `jniLibs` directory).

- [ ] **Step 2: Validate the YAML**

Run:
```bash
python3 -c "import yaml,sys; [yaml.safe_load(open(p)) for p in ['.github/workflows/ci.yml', '.github/workflows/release.yml']]; print('ok')"
```
Expected: `ok`. (If `actionlint` is available, run it too.)

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml .github/workflows/release.yml
git commit -m "ci: build and verify every bundled Unison version per ABI"
```

---

### Task 8: Instrumented binary smoke test, real 2.54 e2e, and docs

**Files:**
- Create: `app/src/androidTest/kotlin/io/unisondroid/app/UnisonBinarySmokeTest.kt`
- Modify: `app/src/androidTest/kotlin/io/unisondroid/app/LocalSyncE2eTest.kt`
- Modify: `README.md`, `native/README.md`, `docs/SERVER-SETUP.md`

**Interfaces:**
- Consumes: `UnisonInfo.BUNDLED`, `libunison_<ver>.so` packaged by Task 6/7.
- Produces: proof that each binary runs and parses.

- [ ] **Step 1: Write the instrumented smoke test**

`UnisonBinarySmokeTest.kt`:
```kotlin
package io.unisondroid.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.unisondroid.app.sync.BinaryLocator
import io.unisondroid.app.sync.BinaryStatus
import io.unisondroid.app.sync.UnisonInfo
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class UnisonBinarySmokeTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun everyBundledUnisonBinaryRunsAndReportsItsVersion() {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        UnisonInfo.BUNDLED.forEach { bundled ->
            val status = BinaryLocator(nativeDir).locate(bundled.fileName)
            assertTrue("${bundled.fileName} must be present in $nativeDir", status is BinaryStatus.Available)
            val process = ProcessBuilder((status as BinaryStatus.Available).path, "-version")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(15, TimeUnit.SECONDS)
            assertTrue("${bundled.fileName} output was: $output", output.contains(bundled.version))
        }
    }
}
```

- [ ] **Step 2: Add a real parser e2e for every bundled version**

In `LocalSyncE2eTest.kt`, add:
```kotlin
    @Test
    fun localSync_worksWithEveryBundledUnisonVersion() {
        val nativeDir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo.nativeLibraryDir,
        )
        UnisonInfo.BUNDLED.forEach { bundled ->
            val status = BinaryLocator(nativeDir).locate(bundled.fileName)
            assertTrue("${bundled.fileName} must be present", status is BinaryStatus.Available)
            val binary = (status as BinaryStatus.Available).path

            val base = newBaseDir()
            val rootA = File(base, "a").apply { mkdirs() }
            val rootB = File(base, "b").apply { mkdirs() }
            val profileDir = File(base, "unison").apply { mkdirs() }
            writeGeneratedProfile(profileDir, PROFILE, rootA, rootB)
            File(rootA, "hello.txt").writeText("hello ${bundled.version}")

            val run = runUnison(binary, profileDir)
            assertEquals("${bundled.version} sync failed:\n${run.output}", 0, run.exit)
            val summary = parseSummary(run.output)
            assertTrue(
                "${bundled.version} output must parse to a completed run; got:\n${run.output}",
                summary.transferred >= 0 && run.output.contains("Synchronization"),
            )
            assertEquals("hello ${bundled.version}", File(rootB, "hello.txt").readText())
        }
    }
```
(Reuse the existing `newBaseDir`, `writeGeneratedProfile`, `runUnison`, and `parseSummary` helpers; `PROFILE` is the existing constant.)

- [ ] **Step 3: Run the instrumented tests (x86_64 emulator)**

Run: `./gradlew :app:connectedDebugAndroidTest --tests '*UnisonBinarySmokeTest' --tests '*LocalSyncE2eTest'`
Expected: PASS for both bundled versions. If 2.54 output fails to parse, capture the real output and adjust `OutputParser` conservatively (fallback + exit code must still complete).

- [ ] **Step 4: Update the docs**

- `README.md`: change "sync to a Unison 2.53.x server" to note the bundled versions (2.54.0, 2.53.8) and that the profile chooses one.
- `native/README.md`: document both bundled versions, the `native/unison-versions.txt` source of truth, the `libunison_<ver>.so` naming rule, and how to add a version (append the tag, rebuild).
- `docs/SERVER-SETUP.md`: add a short table — choose **2.54.0** for servers on 2.52+; choose **2.53.8** for 2.51.x servers.

- [ ] **Step 5: Commit**

```bash
git add app/src/androidTest/kotlin/io/unisondroid/app/UnisonBinarySmokeTest.kt \
  app/src/androidTest/kotlin/io/unisondroid/app/LocalSyncE2eTest.kt \
  README.md native/README.md docs/SERVER-SETUP.md
git commit -m "test+docs: smoke every bundled Unison binary and document selection"
```

---

## Notes for the executor

- The native cross-compile (Task 6) and emulator runs (Task 8) need the full toolchain; Tasks 1–5 and 7's YAML check run anywhere. Do not block app-side tasks on the native build.
- `native/unison-versions.txt` is the contract between the build and the app; if you add or remove a version, update both it and `UnisonInfo.BUNDLED` together (Task 1's parity test enforces this).
- Parser risk for 2.54 is real; Task 8 Step 2 is the guard. If it fails, prefer loosening a regex over adding version branches.
