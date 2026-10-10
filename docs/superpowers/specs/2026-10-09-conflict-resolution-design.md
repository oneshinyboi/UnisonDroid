# Conflict Resolution & Sync Metrics Design

Surface conflicts from Unison, let each profile choose an automatic policy, let
the user resolve individual conflicts, and fix the progress/summary metrics so
the numbers the app displays are correct.

## Context

The app drives the bundled Unison 2.53.8 CLI once per sync with
`unison <profile> -batch` (`SyncEngine.kt:187-190`). In batch mode Unison
propagates every non-conflicting change and skips conflicts; the app then shows
three counters. Three problems make the current behavior both incomplete and
misleading, all confirmed against the pinned source (v2.53.8):

1. **Conflicts are discarded.** `OutputParser` emits `SyncEvent.Conflict` /
   `FailedItem`, but `SyncEngine` keeps only `Progress` and `VersionMismatch`
   (`SyncEngine.kt:207-213`), so conflicts are never shown as anything but a
   count.
2. **The parser does not match real output.** Its fixtures use invented
   `[CONFLICT]` / `[FAILED]` / `conflict:` lines. Real v2.53.8 output uses
   `  skipped: <path> (<reason>)` and `  failed: <path>` (`uitext.ml:1128-1146`),
   and conflict details come from a separate display block. So on a real run the
   conflict/failure counts are wrong.
3. **Progress is wrong.** The parser expects `[wnt] ...  12/345 KiB  path`, which
   Unison never prints. Real text-mode progress is an *overall* percentage that
   Unison computes itself and writes to stdout via `set_infos`
   (`uitext.ml:867-917`, `util.ml:334,73-76`):
   ` 12%   3/10  (1.2 MiB of 4.5 MiB)  250 KiB/s    00:12 ETA`. Because the app
   instead derives progress from per-file `KiB`, the bar reflects a per-file
   fragment and never tracks the whole job.

## Goals

- Parse real v2.53.8 output: conflicts (path + reason + per-side metadata),
  failures, and the completion summary.
- Show conflicts and failures as first-class results (paths, not just counts).
- Per-profile automatic conflict policy (default: skip, as today).
- Per-file conflict resolution after a run, applied through Unison's own
  `preferpartial` / `copyonconflict` preferences.
- Correct sync metrics: overall percentage, transferred/skipped/failed counts,
  and local-time "last sync".

## Non-goals

- Review-before-apply for *all* pending changes (the user chose "conflicts only").
- Text diffs, external merge programs, or a merged file editor.
- Driving the interactive text UI or reproducing unison-gtk's full tree.
- Conflict history beyond the most recent run.

## Mechanism (decided)

Keep the single `-batch` run and parse its output. Resolve with a second,
scoped `-batch` run that embeds `preferpartial = Path <p> -> <root>` for each
user choice. `root2direction` accepts `newer`/`older` or any string that
uniquely identifies a root (host or path suffix) (`recon.ml:82-118`), and
`preferpartial` overrides `prefer` (`recon.ml:168-198`). No TUI, no ANSI prompt
parsing, no long-lived process held across user think-time.

## Architecture

```
SyncEngine.requestSync(profileId)                     (existing entry point)
  └─ run unison <profile> -batch
       └─ OutputParser.feed(line)
            ├─ Progress(pct)            -> SyncState.Syncing(progress)
            ├─ ConflictDisplay(path, sides)   ┐
            ├─ Skipped(path, reason)          ├─> SyncSummary.conflicts
            ├─ Failed(path, msg)              ┘   SyncSummary.failed
            └─ SummaryLine(...)         -> counts
  └─ persist Profile.lastConflicts

SyncEngine.resolveConflicts(profileId, decisions)     (new)
  └─ build augmented prefs (preferpartial / copyonconflict)
  └─ run unison <profile> -batch
  └─ re-parse; resolved paths drop out; remaining reported
```

## Components

### 1. Parser: real v2.53.8 formats (`sync/OutputParser.kt`)

All matching happens after stripping ANSI. Four line families, none of which the
current parser understands:

**Progress** (`uitext.ml:898`):
```
 12%   3/10  (1.2 MiB of 4.5 MiB)  250 KiB/s    00:12 ETA
```
Regex anchored on the trailing `ETA`, capturing percentage and item counts:
`^\s*(\d{1,3})%\s+(\d+)/(\d+)\s+\(([^)]*) of ([^)]*)\)\s+.*ETA\s*$`.
Emit `Progress(fraction = pct / 100.0, label = "<n>/<total>, <bytes>")`.

**Conflict display block** (`uitext.ml:566-585`, `uicommon.ml:360-382`,
`details2string` `uicommon.ml:306-315`):
```
changed  <-?-> changed     notes/plan.txt
local        : changed file     modified on <time>  size 1234 <perms...>
host         : changed file     modified on <time>  size 5678 <perms...>
```
The recon line is fixed-width after ANSI strip:
`<8-char r1> <5-char action> <8-char r2>   <path>  `. Record `path` when the
action is a skip/conflict token (`<-?->` / `<=?=>`). The following lines matching
`^(local|<host>)\s+:\s+(.*)$` are that path's two sides; parse `size <n>` and
`modified on <time>` (the fields come from `Props.toString`,
`props.ml:1413`). A blank line ends the block.

**Skip / failure result lines** (`uitext.ml:1128-1146`):
```
  skipped: notes/plan.txt (conflicting updates)
  failed: docs/report.pdf
Failed [photos/raw/img.cr2]: Input/output error
```
`  skipped: <path> (<reason>)` → conflict if `reason` is one of
`conflicting updates`, `atomic directory`, `properties changed on both sides`,
`contents changed on both sides`, `symbolic links changed on both sides`,
`skip requested` (`recon.ml:681-759`); otherwise a problem. `  failed: <path>`
and `Failed [<path>]: <msg>` → failures.

**Summary** (`uitext.ml:1111-1126`, `1160-1172`):
```
Synchronization complete at 21:33:33  (5 items transferred, 0 skipped, 0 failed)
Synchronization incomplete at 21:33:33  (1 item transferred, 3 skipped, 3 failed)
```
Parse `(complete|incomplete)`, transferred, skipped, failed, optional
`not started`. Both complete and incomplete set the transferred count and emit
`Completed` (incomplete is still a finished run). Anchored on `Synchronization`
so the earlier `N items will be synced, M skipped` line (`uitext.ml:1071`) is
not mistaken for the summary.

Remove the loose heuristics (`line.contains("conflict")`,
`CONFLICT_LINE`, `FILES_TRANSFERRED`) so filenames cannot spoof events.

`SyncSummary` carries lists (see §3); `finalize()` merges display-block records
(sides) with skip records (reason) by path.

### 2. Metrics correctness

- **Progress**: use the percentage Unison computes (`percentageOfTotalSize`,
  `uutil.ml:81`), which weights items and bytes. Reset to 0 at run start; on
  completion set 1.0. Delete the `[wnt] ... KiB` regex.
- **Transferred**: from the summary line (both complete and incomplete); fall
  back to the last progress line's item count only if no summary was seen.
- **Skipped vs conflicts vs problems**: `skipped` count from the summary is a
  cross-check; the displayed conflict list is the subset with a conflict reason.
  Problems are shown as skipped/unresolvable, not counted as conflicts.
- **Failures**: from `failed:` / `Failed [..]` lines; cross-check with the
  summary's failed count.
- **Last sync label**: `lastSyncLabel` (`ProfilesScreen.kt:58-62`) currently
  formats in `ZoneOffset.UTC`; switch to the device's local time zone so the
  displayed timestamp matches wall-clock.
- Add fixtures captured from a real v2.53.8 run (see §7) and an assertion that
  progress reaches 100% and the final counts equal the summary line.

### 3. Data model (`data/Models.kt`)

```kotlin
enum class ConflictPolicy { SKIP, PREFER_NEWER, PREFER_OLDER, PREFER_LOCAL, PREFER_REMOTE, KEEP_BOTH }

@Serializable data class SideInfo(val sizeBytes: Long?, val modifiedAt: Long?, val kind: String?)
@Serializable data class ConflictRecord(val path: String, val reason: String,
                                        val local: SideInfo?, val remote: SideInfo?)
@Serializable data class FailedRecord(val path: String, val message: String)

data class Profile(
    ...
    val conflictPolicy: ConflictPolicy = SKIP,
    val lastConflicts: List<ConflictRecord> = emptyList(),
)

data class SyncSummary(
    val transferred: Int,
    val conflicts: List<ConflictRecord>,
    val failed: List<FailedRecord>,
)
```

`lastConflicts` is rewritten on every run (empty on a clean run) so the resolver
survives navigation and process restart. Existing `Conflict`/`FailedItem`
events are replaced by `ConflictRecord`/`FailedRecord`-shaped events; the old
`SyncSummary(transferred, failed, conflicts)` count constructor is removed.

### 4. Preferences (`sync/PrfGenerator.kt`, new `sync/ConflictResolver.kt`)

Policy → prefs (stage 1), appended by `PrfGenerator` after the existing block:

| ConflictPolicy | prefs written |
|---|---|
| `SKIP` | *(nothing — current behavior)* |
| `PREFER_NEWER` | `prefer = newer` |
| `PREFER_OLDER` | `prefer = older` |
| `PREFER_LOCAL` | `prefer = <localRoot>` |
| `PREFER_REMOTE` | `prefer = ssh://<user>@<host>/<remoteRoot>` |
| `KEEP_BOTH` | `prefer = newer` + `copyonconflict = true` |

`PREFER_OLDER` requires `times` not be false (`recon.ml:200-219`); when it is not
explicitly set we leave the default, and the editor notes the dependency.

`ConflictResolver` (stage 2) builds the resolve run's pref block from decisions:
for each conflict chosen "Keep phone"/"Keep server",
`preferpartial = Path <p> -> <localRoot|host>`; "Keep both" additionally sets
`copyonconflict = true`; "Skip" emits nothing. Pathspecs are emitted with
Unison quoting for spaces/special characters, reusing the quoting approach used
for ignore patterns. The same generated prefs are written to a per-run `.prf`.

### 5. Engine (`sync/SyncEngine.kt`)

- Fold `ConflictDisplay`/`Skipped`/`Failed` events into `SyncSummary` instead of
  dropping them; persist `lastConflicts`; clear it on a clean run.
- Keep the existing non-zero-exit logic; a run with conflicts still exits 0 and
  is `WARNINGS` (unchanged classification), but now carries the conflict list.
- New `resolveConflicts(profileId, decisions: Map<String, Resolution>)`:
  acquires the same `syncMutex`, runs the augmented sync through the existing
  host-key gate / process / cancel machinery, then re-parses and returns the
  remaining conflicts. `resolveConflicts` is rejected (`BUSY`) while a sync is
  running.
- `Resolution` enum: `KEEP_LOCAL, KEEP_REMOTE, KEEP_BOTH, SKIP`.

### 6. UI

- **ProfileEditorScreen**: a "When conflicts occur" dropdown with the six policy
  options and a one-line description each; default `Skip (leave for review)`.
- **RunScreen summary card**: list conflicted paths (reason + local/remote size
  and time) and failed items with messages, instead of counts only; a
  **Resolve conflicts** button when `conflicts` is non-empty.
- **ResolveConflictsScreen** (route `resolve/{id}`, reachable from RunScreen and
  from a profile row whose `lastConflicts` is non-empty): one row per conflict
  with **Keep phone / Keep server / Keep both / Skip**, a per-row metadata line,
  and a **Sync these choices** action that calls `resolveConflicts` and shows
  progress and the remaining conflicts. Reuses the RunScreen progress UI.

## Model/UI changes summary

- `Profile` gains `conflictPolicy` and `lastConflicts` (JSON-compatible defaults).
- `SyncSummary` becomes list-based; `RunScreen` and tests updated.
- `ProfilesScreen` shows a conflict count for profiles whose last run had
  conflicts and formats the last-sync time in local time.
- Navigation gains `Routes.RESOLVE = "resolve/{id}"`.

## Testing

- **Unit**: parser against real captured fixtures (progress, conflict display
  block with all five reasons, failures, `Failed [...]`, complete + incomplete
  summaries, ANSI/no-ANSI, `\r` progress); policy→prefs mapping; `preferpartial`
  quoting for paths with spaces/quotes/leading dashes; `SyncEngine` collects and
  persists conflicts and clears them on clean runs; `resolveConflicts` with a
  fake runner (each decision, and a path that fails to resolve).
- **Fixtures**: capture real v2.53.8 stdout for a conflict run (both roots) and
  replace `app/src/test/resources/unison-output/*.txt`. Add a comment tying
  fixtures to `UnisonInfo.UNISON_VERSION`.
- **Instrumented/E2E**: extend `LocalSyncE2eTest` to produce a conflict, assert
  it is listed with metadata, resolve it toward each side and keep-both, and
  assert the percentage reaches 100% on a normal run.

## Risks and mitigations

- **Format drift** across Unison versions: pinned at 2.53.8; fixtures and
  `UnisonInfo.UNISON_VERSION` are bumped together, and the parser falls back to
  local-only metadata when a display block is absent.
- **`preferpartial` pathspec quoting**: tested with adversarial paths; if a
  decision fails to resolve, the path simply remains in the conflict list and is
  reported, never silently mis-applied.
- **Progress lines arrive via `\r`** with a clearing prefix: `readLine()` splits
  on `\r`; empty/whitespace fragments are ignored. Covered by a fixture.
- **`silent`/`terse` in `advancedPrefs`** suppress details: conflict paths still
  parse from `skipped:` lines; metadata degrades to local-only.

## Milestones

1. Parser rewrite + real fixtures (conflicts, failures, summary) and metrics
   fixes (progress %, counts, local-time last-sync).
2. Data model + `PrfGenerator` policy + editor dropdown (stage 1 shippable).
3. `SyncEngine` conflict capture/persistence + RunScreen conflict list.
4. `ConflictResolver` + `ResolveConflictsScreen` + navigation (stage 2).
5. E2E coverage, fixture pinning, cleanup of the old count-based `SyncSummary`.

## Cleanup

- Remove `SyncEvent.Conflict`, `SyncEvent.FailedItem`, `CONFLICT_MARKER`,
  `CONFLICT_LINE`, `FILES_TRANSFERRED`, and the `[wnt]` `PROGRESS` regex.
- Remove the fixed three-counter summary from `RunScreen`.
- Replace the idealized `unison-output` fixtures.
