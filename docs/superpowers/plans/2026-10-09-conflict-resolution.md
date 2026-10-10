# Conflict Resolution & Sync Metrics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Parse Unison's real output so conflicts, failures, and progress are correct, let each profile choose an automatic conflict policy, and let the user resolve individual conflicts per file.

**Architecture:** Keep the single `unison <profile> -batch` run and parse its stdout. Conflicts are surfaced from `  skipped:` lines + the conflict display block. Per-file resolution is applied by a second scoped `-batch` run that embeds `preferpartial = Path <p> -> <root>` preferences. No TUI, no long-lived process.

**Tech Stack:** Kotlin, Jetpack Compose, kotlinx.serialization, kotlinx.coroutines, WorkManager, JUnit 5 + Robolectric (JVM), AndroidX test (instrumented).

**Spec:** `docs/superpowers/specs/2026-10-09-conflict-resolution-design.md`

## Global Constraints

- Unison is pinned at **2.53.8** (`UnisonInfo.UNISON_VERSION`); every output-format regex and fixture is tied to that version. Update them together on bump.
- Default `ConflictPolicy` is **SKIP** (no automatic resolution).
- Scope is **conflicts only**; non-conflicting changes keep propagating automatically in the batch run.
- Existing hardcoded prefs stay: `perms = 0`, `links = false`, `fat = true`.
- No new third-party dependencies.
- `minSdk 26`, `compileSdk 36`, `targetSdk 36`, JDK 21.
- New `Profile` fields must have serialization defaults so existing profile JSON still deserializes.
- Unit tests: `./gradlew :app:testDebugUnitTest` (JUnit 5). Instrumented: `./gradlew :app:connectedDebugAndroidTest`.

## Review Focus

Inputs/conditions the spec implies but its tests may not exercise; each line gets a test in the owning task.

1. **Glob metacharacters in conflict paths** (`* ? [ ] { } ,`) — `preferpartial` uses `Path` = `Rx.globx`, so these must be backslash-escaped or a literal path matches other files. (Task 7)
2. **Spaces / unicode / leading dashes in paths** — must survive parse → `preferpartial` round-trip and not be split by the ` -> ` separator. (Tasks 4, 7)
3. **`silent`/`terse` in `advancedPrefs`** suppress the display block — conflict paths must still come from `skipped:` lines; metadata degrades to local-only. (Task 4)
4. **`\r`-delimited and chunk-split output** — progress and conflict lines interleave; no lost lines, no spurious events. (Tasks 1, 4)
5. **A decision that fails to resolve** (problem item, or Unison still skips) — the path stays listed after the resolve run; never silently dropped. (Task 8)

---

### Task 1: Real progress metric

**Files:**
- Modify: `app/src/main/kotlin/io/unisondroid/app/sync/OutputParser.kt`
- Test: `app/src/test/kotlin/io/unisondroid/app/sync/OutputParserTest.kt`
- Test: `app/src/test/resources/unison-output/happy-path.txt` (replace), `app/src/test/resources/unison-output/progress.txt` (new)

**Interfaces:**
- Consumes: nothing new.
- Produces: `SyncEvent.Progress(fraction: Float, label: String)` where `fraction` is Unison's overall percentage / 100 and `label` is the raw progress text.

- [ ] **Step 1: Add a failing test for the real progress line**

New fixture `progress.txt` (real v2.53.8 text-mode output, `\r` separated is fine — `UnisonRunner`'s `readLine()` splits on `\r`):
```
 12%   3/10  (1.2 MiB of 4.5 MiB)  250 KiB/s    00:12 ETA
100%  10/10  (4.5 MiB of 4.5 MiB)  00:00:00 ETA
```
In `OutputParserTest`:
```kotlin
@Test fun `real progress line yields overall fraction`() {
    val (parser, events) = feedAll(fixture("progress.txt"))
    assertEquals(0.12f, (events[0] as SyncEvent.Progress).fraction, 0.001f)
    assertEquals(1.0f, (events[1] as SyncEvent.Progress).fraction, 0.001f)
}
```

- [ ] **Step 2: Run it and verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.sync.OutputParserTest"`
Expected: FAIL (progress regex `/KiB/` matches none of the new lines).

- [ ] **Step 3: Replace the progress regex and handler**

In `OutputParser`, replace `PROGRESS` with:
```kotlin
private val PROGRESS = Regex("""^\s*(\d{1,3})%\s+(\d+)/(\d+)\s+\(([^)]*) of ([^)]*)\)\s+.*ETA\s*$""")
```
and emit `SyncEvent.Progress(pct/100f, line)` where `pct` = group 1. Keep `finalize`'s transferred fallback using the last progress line's `n`.

- [ ] **Step 4: Replace `happy-path.txt` and update dependent assertions**

Rewrite `happy-path.txt` with real-format lines and update `OutputParserTest` expectations (`SyncEngineTest.PROGRESS_LINE` becomes `" 50%   5/10  (5.0 MiB of 10 MiB)  1.0 MiB/s    00:05 ETA"`). Remove the old `[wnt] ... KiB` fixture expectations.

- [ ] **Step 5: Run the parser and engine tests**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.sync.*"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/io/unisondroid/app/sync/OutputParser.kt app/src/test/kotlin/io/unisondroid/app/sync/OutputParserTest.kt app/src/test/kotlin/io/unisondroid/app/sync/SyncEngineTest.kt app/src/test/resources/unison-output
git commit -m "fix: parse unison's real overall progress line"
```

---

### Task 2: Summary, failure, and skip counts

**Files:**
- Modify: `app/src/main/kotlin/io/unisondroid/app/sync/OutputParser.kt`
- Test: `app/src/test/kotlin/io/unisondroid/app/sync/OutputParserTest.kt`
- Test resources: `incomplete.txt`, `failures.txt`, `skips.txt` (new)

**Interfaces:**
- Consumes: nothing new.
- Produces: `SyncSummary(transferred, failed, conflicts)` counts correct for complete **and** incomplete runs; `conflicts` counts only conflict-reason skips.

- [ ] **Step 1: Add failing fixtures/tests**

`incomplete.txt`:
```
 12%   1/4  (1.0 MiB of 4.0 MiB)  1.0 MiB/s    00:03 ETA
  skipped: notes/plan.txt (conflicting updates)
  skipped: broken/ (Syncing symbolic links is disabled)
  failed: docs/report.pdf
Failed [photos/raw/img.cr2]: Input/output error
Synchronization incomplete at 21:33:33  (1 item transferred, 2 skipped, 2 failed)
```
Tests:
```kotlin
@Test fun `incomplete summary still finalizes counts`() {
    assertEquals(SyncSummary(transferred = 1, failed = 2, conflicts = 1), feedFinalize("incomplete.txt"))
}
@Test fun `problem skips are not counted as conflicts`() { /* skips.txt: 1 conflict + 1 problem => conflicts == 1 */ }
```

- [ ] **Step 2: Run and verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.sync.OutputParserTest"`
Expected: FAIL.

- [ ] **Step 3: Implement summary/skip/failure parsing**

- Summary: `Regex("""Synchronization (complete|incomplete) at \d{2}:\d{2}:\d{2}\s+\((\d+) items? transferred, (\d+) skipped, (\d+) failed.*\)""")`; set `transferredFromSummary` and emit `Completed` for both outcomes. Anchored on `Synchronization` so the earlier `N items will be synced, M skipped` line is ignored.
- `Regex("""^\s*skipped:\s+(.*?)\s+\((.*)\)\s*$""")` → conflict if reason in `CONFLICT_REASONS`, else problem; increment `conflicts` only for conflicts.
- `Regex("""^\s*failed:\s+(.*)$""")` and `Regex("""^Failed \[(.*?)]:\s?(.*)$""")` → increment `failed`.
- Delete `CONFLICT_MARKER`, `CONFLICT_LINE`, `FILES_TRANSFERRED`, and the `line.contains("conflict")` / `files?\b` heuristics.
- Define at file scope:
```kotlin
val CONFLICT_REASONS = setOf(
    "conflicting updates", "atomic directory",
    "properties changed on both sides", "contents changed on both sides",
    "symbolic links changed on both sides", "skip requested",
)
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.sync.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -am "fix: parse unison summary, failure, and skip lines from real output"
```

---

### Task 3: Conflict records model, list-based summary, persistence

**Files:**
- Modify: `app/src/main/kotlin/io/unisondroid/app/data/Models.kt`
- Modify: `app/src/main/kotlin/io/unisondroid/app/sync/OutputParser.kt`
- Modify: `app/src/main/kotlin/io/unisondroid/app/sync/SyncEngine.kt`
- Modify: `app/src/main/kotlin/io/unisondroid/app/ui/RunScreen.kt`
- Test: `OutputParserTest.kt`, `SyncEngineTest.kt`, `RunScreenTest.kt`

**Interfaces:**
- Consumes: `CONFLICT_REASONS` (Task 2).
- Produces:
```kotlin
@Serializable data class SideInfo(val sizeBytes: Long? = null, val modifiedAt: Long? = null, val kind: String? = null)
@Serializable data class ConflictRecord(val path: String, val reason: String = "",
                                       val local: SideInfo? = null, val remote: SideInfo? = null,
                                       val resolvable: Boolean = true)
@Serializable data class FailedRecord(val path: String, val message: String = "")
data class SyncSummary(val transferred: Int, val conflicts: List<ConflictRecord>, val failed: List<FailedRecord>)
```
`Profile.lastConflicts: List<ConflictRecord> = emptyList()`.

- [ ] **Step 1: Write failing tests**

`OutputParserTest`:
```kotlin
@Test fun `skip line becomes a resolvable conflict record`() {
    val s = feedFinalize("skips.txt")
    assertEquals(listOf(ConflictRecord("notes/plan.txt", "conflicting updates", resolvable = true)),
        s.conflicts.filter { it.resolvable })
    assertTrue(s.conflicts.any { !it.resolvable })  // the symbolic-link problem
}
```
`SyncEngineTest`: assert `(state as Finished).summary.conflicts` carries the record and `repo.get("prof1")!!.lastConflicts` is persisted; a clean run persists an empty list.

- [ ] **Step 2: Run and verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.sync.*"`
Expected: FAIL (compile error: `SyncSummary` shape changed).

- [ ] **Step 3: Change the model and thread it through**

- Add the records + `lastConflicts` to `Profile`.
- `SyncSummary` becomes list-based; `SyncEvent.Conflict`/`FailedItem` are removed (only `Progress`, `VersionMismatch`, `Completed`, `Fatal` remain); `OutputParser` accumulates `LinkedHashMap<String, ConflictRecord>` and `MutableList<FailedRecord>` and returns them from `finalize`.
- `SyncEngine`: after a successful run, `profiles.save(p.copy(lastSyncedAt = clock.millis(), lastResult = result, lastConflicts = summary.conflicts))`; on failure keep prior `lastConflicts`; `OK` iff `summary.failed.isEmpty() && summary.conflicts.isEmpty()`.
- `RunScreen.SummaryCard`: show `${summary.transferred} transferred`, then a row per conflict (`path` + `reason`) and per failed item (`path` + `message`); add test tags `RUN_CONFLICT_ROW`, `RUN_FAILED_ROW`.

- [ ] **Step 4: Update UI tests and run all unit tests**

`RunScreenTest`: construct `SyncSummary(transferred = 3, conflicts = listOf(ConflictRecord("a.txt", "conflicting updates")), failed = listOf(FailedRecord("b.pdf", "boom")))` and assert the paths render.
Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -am "feat: carry conflict and failure records through the summary"
```

---

### Task 4: Per-side conflict metadata from the display block

**Files:**
- Modify: `app/src/main/kotlin/io/unisondroid/app/sync/OutputParser.kt`
- Test: `OutputParserTest.kt`; fixture `display-block.txt` (new)

**Interfaces:**
- Consumes: `ConflictRecord`, `SideInfo` (Task 3).
- Produces: `ConflictRecord.local`/`.remote` populated when the display block is present; `null` when suppressed.

- [ ] **Step 1: Add a failing fixture/test**

`display-block.txt`:
```
changed  <-?-> changed     notes/plan.txt
local        : changed file     modified on 2024-01-02 at 03:04:05  size 1234 -rw-r--r--
host         : changed file     modified on 2024-01-02 at 04:05:06  size 5678 -rw-r--r--

  skipped: notes/plan.txt (conflicting updates)
```
```kotlin
@Test fun `display block fills both sides`() {
    val c = feedFinalize("display-block.txt").conflicts.single()
    assertEquals(1234L, c.local?.sizeBytes)
    assertEquals(5678L, c.remote?.sizeBytes)
    assertNotNull(c.local?.modifiedAt)
    assertEquals("conflicting updates", c.reason)
}
@Test fun `missing display block still yields the conflict`() {
    val c = feedFinalize("skips.txt").conflicts.first { it.path == "notes/plan.txt" }
    assertNull(c.local); assertNull(c.remote)
}
```

- [ ] **Step 2: Run and verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.sync.OutputParserTest"`
Expected: FAIL (sides null).

- [ ] **Step 3: Implement block parsing**

- Recon line: `Regex("""^(.{8}) (error|[-<=>?M]{5}) (.{8}) {3}(.*?)\s*$""")`; when the action is `<-?->`/`<=?=>`, set `pendingPath = group(4)` and reset sides.
- Detail line: `Regex("""^(local|\S.*?)\s+:\s+(.*)$""")` only while a `pendingPath` is set; parse `size (\d+)` and `modified on (.+?)\s\s+size`; map the first side to `local`, second to `remote` (the app always writes the local root first, `PrfGenerator.kt:15`).
- A blank line or a new recon line closes the block.
- On the `skipped:` line, merge `reason`/`resolvable` into the record for that path (Task 2 path), creating it if the block was suppressed.

- [ ] **Step 4: Run tests, including a `\r`-split variant**

Add a test feeding `display-block.txt` in 1-char chunks; assert the same record.
Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.sync.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -am "feat: parse per-side conflict metadata from the display block"
```

---

### Task 5: Conflict policy model and prf mapping

**Files:**
- Modify: `app/src/main/kotlin/io/unisondroid/app/data/Models.kt`
- Modify: `app/src/main/kotlin/io/unisondroid/app/sync/PrfGenerator.kt`
- Test: `PrfGeneratorTest.kt`

**Interfaces:**
- Consumes: `Profile` (Task 3).
- Produces: `enum class ConflictPolicy { SKIP, PREFER_NEWER, PREFER_OLDER, PREFER_LOCAL, PREFER_REMOTE, KEEP_BOTH }`; `Profile.conflictPolicy: ConflictPolicy = ConflictPolicy.SKIP`.

- [ ] **Step 1: Write failing tests**

```kotlin
@Test fun `skip policy writes no conflict prefs`() {
    assertFalse(PrfGenerator.generate(profile(), sshCommand()).contains("prefer"))
}
@Test fun `prefer newer writes prefer newer`() {
    assertTrue(PrfGenerator.generate(profile(policy = ConflictPolicy.PREFER_NEWER), sshCommand())
        .lines().contains("prefer = newer"))
}
@Test fun `prefer remote names the ssh root exactly`() {
    val out = PrfGenerator.generate(profile(policy = ConflictPolicy.PREFER_REMOTE), sshCommand())
    assertTrue(out.lines().contains("prefer = ssh://diamond@server.example//srv/sync"))
}
@Test fun `keep both prefers newer and copies on conflict`() {
    val lines = PrfGenerator.generate(profile(policy = ConflictPolicy.KEEP_BOTH), sshCommand()).lines()
    assertTrue(lines.contains("prefer = newer")); assertTrue(lines.contains("copyonconflict = true"))
}
```

- [ ] **Step 2: Run and verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.sync.PrfGeneratorTest"`
Expected: FAIL (no policy param).

- [ ] **Step 3: Implement the enum, field, and mapping**

Add `conflictPolicyPreferences(profile): List<String>` returning the lines per the table in the spec (§4) and append them after the existing block in `generate`. `PREFER_LOCAL` uses `profile.localRoot`; `PREFER_REMOTE` reuses the exact `ssh://<user>@<host>/<remoteRoot>` string already emitted for `root`.

- [ ] **Step 4: Run tests, then the existing exact-output tests**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.sync.PrfGeneratorTest"`
Expected: PASS; the existing `empty ignores and advanced leave no blank residue` test still passes because `SKIP` emits nothing.

- [ ] **Step 5: Commit**

```bash
git commit -am "feat: per-profile conflict policy in the generated prf"
```

---

### Task 6: Profile editor conflict-policy dropdown

**Files:**
- Modify: `app/src/main/kotlin/io/unisondroid/app/ui/ProfileEditorScreen.kt`
- Test: `ProfileEditorScreenTest.kt`

**Interfaces:**
- Consumes: `ConflictPolicy` (Task 5).
- Produces: test tag `FIELD_CONFLICT_POLICY = "editor-conflictPolicy"`; the editor preserves `initial.conflictPolicy` and writes the selection.

- [ ] **Step 1: Write a failing test**

```kotlin
@Test fun `conflict policy round-trips through the editor`() {
    runBlocking { repository.save(seedProfile().copy(conflictPolicy = ConflictPolicy.PREFER_NEWER)) }
    // render editor for "p1", click the policy button, choose "Prefer newer", save
    compose.waitUntil(5_000) {
        runBlocking { repository.get("p1")?.conflictPolicy == ConflictPolicy.PREFER_NEWER }
    }
}
```

- [ ] **Step 2: Run and verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.ui.ProfileEditorScreenTest"`
Expected: FAIL (tag absent).

- [ ] **Step 3: Implement**

Add a labelled dropdown (pattern from the auto-sync interval menu, `ProfileEditorScreen.kt:382-401`) after "Advanced", defaulting to `initial?.conflictPolicy ?: SKIP`, and carry it into the constructed `Profile` in `save()`.

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.ui.ProfileEditorScreenTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -am "feat: conflict-policy selector in the profile editor"
```

---

### Task 7: ConflictResolver preference generation

**Files:**
- Create: `app/src/main/kotlin/io/unisondroid/app/sync/ConflictResolver.kt`
- Test: `app/src/test/kotlin/io/unisondroid/app/sync/ConflictResolverTest.kt`

**Interfaces:**
- Consumes: `Profile`, `ConflictRecord`.
- Produces: `enum class Resolution { KEEP_LOCAL, KEEP_REMOTE, KEEP_BOTH, SKIP }` and
  `object ConflictResolver { fun resolutionPreferences(profile: Profile, decisions: Map<String, Resolution>): List<String> }`.

- [ ] **Step 1: Write failing tests**

```kotlin
@Test fun `keep local and remote emit preferpartial with exact roots`() {
    val prefs = ConflictResolver.resolutionPreferences(
        profile(), mapOf("a.txt" to Resolution.KEEP_LOCAL, "b/c.txt" to Resolution.KEEP_REMOTE))
    assertTrue(prefs.contains("preferpartial = Path a.txt -> /storage/emulated/0/Sync"))
    assertTrue(prefs.contains("preferpartial = Path b/c.txt -> ssh://diamond@server.example//srv/sync"))
}
@Test fun `glob metacharacters in a path are escaped`() {
    val prefs = ConflictResolver.resolutionPreferences(profile(), mapOf("a[1]*.txt" to Resolution.KEEP_LOCAL))
    assertTrue(prefs.any { it == """preferpartial = Path a\[1\]\*.txt -> /storage/emulated/0/Sync""" })
}
@Test fun `keep both prefers newer and sets copyonconflict once`() {
    val prefs = ConflictResolver.resolutionPreferences(
        profile(), mapOf("a" to Resolution.KEEP_BOTH, "b" to Resolution.KEEP_BOTH))
    assertEquals(1, prefs.count { it == "copyonconflict = true" })
    assertTrue(prefs.contains("preferpartial = Path a -> newer"))
}
@Test fun `skip emits nothing for that path`() {
    assertTrue(ConflictResolver.resolutionPreferences(profile(), mapOf("a" to Resolution.SKIP)).isEmpty())
}
```

- [ ] **Step 2: Run and verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.sync.ConflictResolverTest"`
Expected: FAIL (unresolved reference).

- [ ] **Step 3: Implement**

- Escape each of `\ * ? [ ] { } ,` in the path with a leading `\` (required because `Pred` `Path` is `Rx.globx`, which honours `\` escapes; see spec risks).
- `KEEP_LOCAL` → `preferpartial = Path <escaped> -> <localRoot>`.
- `KEEP_REMOTE` → `preferpartial = Path <escaped> -> ssh://<user>@<host>/<remoteRoot>`.
- `KEEP_BOTH` → `preferpartial = Path <escaped> -> newer` plus a single `copyonconflict = true` when any keep-both is present.
- `SKIP` → no line.

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.sync.ConflictResolverTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -am "feat: generate preferpartial preferences for conflict decisions"
```

---

### Task 8: `SyncEngine.resolveConflicts`

**Files:**
- Modify: `app/src/main/kotlin/io/unisondroid/app/sync/SyncEngine.kt`
- Test: `SyncEngineTest.kt`

**Interfaces:**
- Consumes: `ConflictResolver.resolutionPreferences`, `Resolution` (Task 7), `ConflictRecord` (Task 3).
- Produces: `open suspend fun resolveConflicts(profileId: String, decisions: Map<String, Resolution>): SyncOutcome` returning `COMPLETED` when `lastConflicts` is empty afterwards, `FAILED` on error, `BUSY` if a run is active; updates `Profile.lastConflicts`.

- [ ] **Step 1: Write failing tests** (use the existing `harness`/`FakeRunner`)

```kotlin
@Test fun `resolve run appends preferpartial and clears resolved conflicts`() = runTest {
    // first run yields a conflict; resolve run yields a clean summary
    // assert the resolve run's prf contains "preferpartial = Path notes/plan.txt -> ..."
    // assert repo.lastConflicts is empty and outcome COMPLETED
}
@Test fun `resolve run keeps unresolved conflicts`() = runTest {
    // resolve run still emits the same "  skipped:" line -> lastConflicts unchanged
}
@Test fun `resolve while syncing returns BUSY`() = runTest { /* gate the first run, expect BUSY */ }
```

- [ ] **Step 2: Run and verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.sync.SyncEngineTest"`
Expected: FAIL (unresolved reference).

- [ ] **Step 3: Implement**

Extract the existing run body so both `requestSync` and `resolveConflicts` share the host-key gate / process / parse path. `resolveConflicts` acquires `syncMutex` via `tryLock()` (else `BUSY`), writes the prf with the policy prefs plus `ConflictResolver.resolutionPreferences(profile, decisions)` appended, runs, re-parses, and saves `lastConflicts = summary.conflicts`.

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.sync.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -am "feat: resolve conflicts via a scoped preferpartial run"
```

---

### Task 9: Resolve-conflicts screen and navigation

**Files:**
- Create: `app/src/main/kotlin/io/unisondroid/app/ui/ResolveConflictsScreen.kt`
- Modify: `app/src/main/kotlin/io/unisondroid/app/ui/Navigation.kt`
- Modify: `app/src/main/kotlin/io/unisondroid/app/ui/RunScreen.kt`
- Modify: `app/src/main/kotlin/io/unisondroid/app/ui/ProfilesScreen.kt`
- Test: `app/src/test/kotlin/io/unisondroid/app/ui/ResolveConflictsScreenTest.kt`, `NavigationTest.kt`, `RunScreenTest.kt`

**Interfaces:**
- Consumes: `SyncEngine.resolveConflicts`, `Resolution`, `Profile.lastConflicts`.
- Produces: route `Routes.RESOLVE = "resolve/{id}"`; `fun resolve(id: String): String`; tags `RESOLVE_ROW_PREFIX`, `RESOLVE_SYNC_TAG`, `RESOLVE_KEEP_LOCAL`, `RESOLVE_KEEP_REMOTE`, `RESOLVE_KEEP_BOTH`, `RESOLVE_SKIP`.

- [ ] **Step 1: Write failing tests**

`ResolveConflictsScreenTest`: seed a profile with two `lastConflicts`; render; assert both paths + reasons appear; select `KEEP_LOCAL` on the first and assert `resolveConflicts` was called with `{path -> KEEP_LOCAL}` (fake engine records the map).
`RunScreenTest`: a finished summary with conflicts shows a `Resolve conflicts` button that navigates.
`NavigationTest`: navigating to `Routes.resolve("p1")` renders the resolver.

- [ ] **Step 2: Run and verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.ui.*"`
Expected: FAIL.

- [ ] **Step 3: Implement**

- `ResolveConflictsScreen(profileId)`: load `Profile.lastConflicts`; one row per record showing `path`, `reason`, and local/remote `sizeBytes`/`modifiedAt`; four-choice selector; "Sync these choices" calls `resolveConflicts` and shows the existing progress UI; resolvable=false rows show "cannot resolve automatically" and disable the action.
- `Navigation`: add the route; `RunScreen` gains a `Resolve conflicts` button (tag `RUN_RESOLVE_TAG`) when `summary.conflicts.any { it.resolvable }`; `ProfilesScreen` row shows a conflicts count when `lastConflicts` is non-empty.

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.ui.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -am "feat: resolve-conflicts screen wired into navigation"
```

---

### Task 10: Last-sync label in local time

**Files:**
- Modify: `app/src/main/kotlin/io/unisondroid/app/ui/ProfilesScreen.kt`
- Test: `ProfilesScreenTest.kt`

**Interfaces:**
- Consumes: `Profile.lastSyncedAt`.
- Produces: `lastSyncLabel` formatted in the device's local zone.

- [ ] **Step 1: Write a failing test**

```kotlin
@Test fun `last sync label uses the local time zone`() {
    assertEquals("Last sync: 2024-01-01 12:00", lastSyncLabel(epochMillisForLocalNoon()))
}
```
(compute the expected millis from `LocalDateTime`/system zone, or set the JVM zone with `TimeZone.setDefault` in the test.)

- [ ] **Step 2: Run and verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.ui.ProfilesScreenTest"`
Expected: FAIL.

- [ ] **Step 3: Implement**

Change `LAST_SYNC_FORMATTER` (`ProfilesScreen.kt:58-59`) to `.withZone(ZoneId.systemDefault())`.

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "io.unisondroid.app.ui.ProfilesScreenTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -am "fix: show last-sync time in the device's local time zone"
```

---

### Task 11: Instrumented coverage and cleanup

**Files:**
- Modify: `app/src/androidTest/kotlin/io/unisondroid/app/LocalSyncE2eTest.kt`
- Delete: dead parser code/fixtures
- Modify: `app/src/main/kotlin/io/unisondroid/app/sync/OutputParser.kt`

**Interfaces:**
- Consumes: everything above.

- [ ] **Step 1: Add an instrumented test that produces and resolves a real conflict**

In `LocalSyncE2eTest`, using the existing local-local profile helper: write the same file with different content in both roots, run unison, feed the captured output to `OutputParser`, assert the conflict record and its sides parse; then run again with a `preferpartial = Path <p> -> <rootB>` pref line appended to the prf and assert both roots converge to `rootB`'s content.

- [ ] **Step 2: Run instrumented tests (emulator + built native libs)**

Run: `./gradlew :app:connectedDebugAndroidTest`
Expected: PASS (or skipped if no device, matching current behavior).

- [ ] **Step 3: Remove dead code**

Delete `SyncEvent.Conflict`/`FailedItem` remnants, `CONFLICT_MARKER`, `CONFLICT_LINE`, `FILES_TRANSFERRED`, the old `PROGRESS` regex, the idealized `conflicts-failures.txt`/`version-mismatch.txt` fixtures if superseded, and the old count-only `SyncSummary` usages. Grep to confirm none remain:
```bash
grep -rn "\[CONFLICT\]\|\[FAILED\]\|FILES_TRANSFERRED\|wnt\]" app/src
```
Expected: no matches.

- [ ] **Step 4: Run the full unit suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -am "test: real-binary conflict resolution e2e; remove idealized parser fixtures"
```

---

## Self-Review

- **Spec coverage:** §1 parser → Tasks 1,2,4; §2 metrics → Tasks 1,2,10; §3 model → Tasks 3,5; §4 prefs → Tasks 5,7; §5 engine → Tasks 3,8; §6 UI → Tasks 6,9; testing/cleanup → Tasks 1–11. No gaps.
- **Type consistency:** `ConflictRecord`/`SideInfo`/`FailedRecord`/`SyncSummary`/`ConflictPolicy`/`Resolution` and the `resolveConflicts` signature are defined once (Tasks 3,5,7,8) and reused verbatim.
- **Review Focus:** each of the five lines has an owning test (Task 7 metacharacters/round-trip; Task 4 metadata + `\r` chunks + suppressed block; Task 8 unresolved-decision; Task 7 unicode/spaces; Task 1 `\r` interleave).
- **Proportion:** tasks carry signatures and test assertions, not full bodies.
