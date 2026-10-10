# Multi-Version Unison Support — Design

**Date:** 2026-10-10
**Status:** Draft for review
**Related:** `docs/superpowers/specs/2026-10-08-unisondroid-design.md`,
`docs/superpowers/specs/2026-10-09-conflict-resolution-design.md`

## Summary

Bundle two Unison binaries — **2.53.8** and **2.54.0** — per ABI and let each
profile choose which one to run. Default is 2.54.0. On a version-mismatch
failure the run screen offers one-tap "retry with the other version".

This lets one install talk both to modern servers (2.52+) and to legacy
2.51-era servers, which a single 2.54 binary cannot reach.

## Why these two versions

Unison's compatibility model changed in 2.52.0:

- **2.52.0** introduced a new, compiler-independent wire protocol plus
  **feature negotiation**. Cross-version operation is supported for all
  versions ≥ 2.52.0 (`NEWS.md`: *"Feature negotiation, compatible with 2.51"*;
  man page: *"If you are using Unison versions ≥ 2.52 on all machines, you do
  not have to do anything extra for compatibility"*).
- **2.54.0 dropped the old wire protocol**: *"Unison will no longer
  interoperate with versions before 2.52.0"*. The 2.51-compatibility path
  (RPC version 0) is gone.
- **2.53.8 retains 2.51-compatibility mode** (`src/remote.ml`: *"Version 0 …
  is used for 2.51-compatibility mode and is never negotiated"*), so it can
  still reach 2.51.x servers.

Resulting coverage:

| Server version | Handled by |
| --- | --- |
| 2.51.x | 2.53.8 (compat mode) |
| 2.52.x | 2.53.8 and 2.54.0 |
| 2.53.x | 2.53.8 and 2.54.0 |
| 2.54.x and future | 2.54.0 |
| ≤ 2.50 | not covered (ancient; each old minor would need its own exact-match binary) |

A native 2.51.5 binary would give the same 2.51.x coverage as 2.53.8 but with
worse parser fidelity (the parser is tuned to 2.53.8), a less reliable build
recipe, and no 2.52/2.53 fallback. It is intentionally excluded.

## Non-goals

- Covering servers older than 2.51 (2.48–2.50).
- Full per-version `OutputParser` parity. The parser stays tuned to 2.53.8;
  2.54 relies on the existing fallback + exit-code classification.
- Auto-detecting the server version by probing the remote host. Selection is
  explicit; the mismatch flow offers a one-tap switch.
- ABI splits. A single universal APK is kept (follow-up if size becomes a
  problem).
- Download-on-demand binaries. Android's W^X restriction forbids `execve()` on
  app-private files for targetSdk ≥ 29, so binaries must ship inside the APK's
  `jniLibs` (the app already exploits this by naming them `lib*.so`).

## Design

### 1. Bundled version registry

`native/unison-versions.txt` is the single source of truth for *which* versions
are bundled — one release tag per line, newest first:

```
v2.54.0
v2.53.8
```

The native build script and CI loop over this file. On the app side,
`UnisonInfo` is a hand-written registry mapping each version to its packaged
file name:

```kotlin
package io.unisondroid.app.sync

data class BundledUnison(val version: String, val fileName: String)

object UnisonInfo {
    val BUNDLED = listOf(
        BundledUnison("2.54.0", "libunison_2_54_0.so"),
        BundledUnison("2.53.8", "libunison_2_53_8.so"),
    )
    val DEFAULT = BUNDLED.first()

    /** Unknown or blank versions fall back to the default. */
    fun forVersion(version: String): BundledUnison =
        BUNDLED.firstOrNull { it.version == version } ?: DEFAULT
}
```

Naming rule: `libunison_` + version with `.` → `_` + `.so`
(`v2.54.0` → `libunison_2_54_0.so`). All names start with `lib` and end with
`.so` so Android extracts them into `nativeLibraryDir` with execute permission.

A unit test asserts that the set of versions in `UnisonInfo.BUNDLED` equals the
set parsed from `../native/unison-versions.txt` (and that the file-name encoding
matches the rule), so the app and the build cannot drift.

### 2. Profile field and migration

`Profile` gains:

```kotlin
val unisonVersion: String = "",
```

Blank means "use `UnisonInfo.DEFAULT`", resolved at run time. The `data` package
therefore does not need to depend on `sync`. `JsonStore` already uses
`ignoreUnknownKeys = true` and `encodeDefaults = true`, so existing profiles
read as blank and adopt the default with no migration code. The editor always
writes an explicit version for new/edited profiles.

### 3. Binary resolution and engine

- `BinaryLocator` gains `locate(fileName: String): BinaryStatus`, resolving the
  named file in `nativeLibraryDir`. (`fileName` comes from the registry, so the
  naming rule has a single home.) `locateSsh()` is unchanged; the old no-arg
  `locate()` is removed.
- `SyncEngine.locateBinaries(profile)` resolves the profile's version to a
  `BundledUnison` via `UnisonInfo.forVersion(profile.unisonVersion)` (blank or
  unknown → `DEFAULT`), locates `bundled.fileName`, and reports
  `BINARY_MISSING` naming the expected file on absence.
- `runSync` is unchanged: it already receives the resolved `File`.

### 4. Native build and packaging

- `native/build-unison.sh`:
  - Read the tag from the environment (`UNISON_VERSION`), defaulting to the
    current pinned tag.
  - Derive `OUT_BIN` and the `jniLibs` file name from the tag via the naming
    rule above (both `<abi>/libunison_<ver>.so`).
  - Keep the existing host/cross OCaml steps; the cross OCaml toolchain and host
    OCaml are shared across versions (built once).
- CI (`.github/workflows/ci.yml`, `.github/workflows/release.yml`):
  - Native matrix becomes `{arm64-v8a, x86_64} × {tags from
    native/unison-versions.txt}`.
  - The verify step checks every bundled `libunison_*.so` plus the OpenSSH
    binaries for each ABI.
  - Cache key already includes `UNISON_VERSION`; extend it to hash
    `native/unison-versions.txt` so a new version busts the cache.
- OpenSSH binaries (`libssh*.so`) are version-independent; unchanged.
- `app/build.gradle.kts` keeps `useLegacyPackaging = true` (required so the
  binaries are extracted to an executable location); comment updated.

### 5. UI

- **Profile editor** (`ProfileEditorScreen.kt`): a "Unison version" dropdown
  modelled on the existing conflict-policy dropdown, listing `UnisonInfo.BUNDLED`
  with the default preselected. Saves to `Profile.unisonVersion`.
- **About** (`AboutScreen.kt`): list every bundled version and its file name,
  replacing the single `UNISON_VERSION` line.
- **Run screen** (`RunScreen.kt`): for a `SyncState.Failed` with
  `Reason.VERSION`, `FailureCard` shows a short explanation plus one button per
  *other* bundled version ("Try Unison 2.53.8"). Tapping it saves the new
  version onto the profile and starts a fresh run of the same variant. This is
  the "easy selection" path: no version numbers to reason about.

### 6. Error handling

- Missing binary for the selected version → `BINARY_MISSING` with the expected
  file name (existing card, updated copy).
- Version mismatch → `VERSION` with the raw Unison detail (unchanged), plus the
  retry-with-other-version affordance from §5.
- Unknown/blank version string (e.g. hand-edited JSON) → falls back to
  `UnisonInfo.DEFAULT`.

### 7. Testing

- **Unit (JVM):**
  - `BinaryLocator.locate(fileName)` returns `Available`/`Missing` per file.
  - `UnisonInfo.BUNDLED` ↔ `native/unison-versions.txt` parity + naming rule.
  - `UnisonInfo.forVersion` maps blank/unknown versions to `DEFAULT`.
  - `SyncEngine` runs with the profile's selected binary (runner factory
    receives the expected file).
  - `Profile` with a blank/absent `unisonVersion` deserializes and resolves to
    the default.
  - Existing `OutputParser` tests stay green (unchanged).
- **Instrumented (x86_64 emulator):**
  - Both `libunison_*.so` execute and print the expected `-version`.
  - A profile pinned to each version completes a run against the test server.
- **Native verify:** each ABI's `jniLibs` contains every bundled
  `libunison_*.so` and the OpenSSH binaries.
- **Fixture:** capture one real 2.54.0 output sample and add a smoke test that
  the parser classifies it without error (best-effort; full parity out of scope).

### 8. Docs

- `README.md`: status line mentions both bundled versions.
- `native/README.md`: two versions, the naming rule, and how to add a new one
  (append to `native/unison-versions.txt`, rebuild).
- `docs/SERVER-SETUP.md`: which app version to choose for which server version.
- About screen copy (§5).

## Files touched

- `native/unison-versions.txt` (new)
- `native/build-unison.sh`
- `native/README.md`
- `.github/workflows/ci.yml`, `.github/workflows/release.yml`
- `app/src/main/kotlin/io/unisondroid/app/sync/UnisonInfo.kt`
- `app/src/main/kotlin/io/unisondroid/app/sync/UnisonRunner.kt` (`BinaryLocator`)
- `app/src/main/kotlin/io/unisondroid/app/sync/SyncEngine.kt`
- `app/src/main/kotlin/io/unisondroid/app/data/Models.kt` (`Profile`)
- `app/src/main/kotlin/io/unisondroid/app/ui/ProfileEditorScreen.kt`
- `app/src/main/kotlin/io/unisondroid/app/ui/AboutScreen.kt`
- `app/src/main/kotlin/io/unisondroid/app/ui/RunScreen.kt`
- `app/build.gradle.kts` (comment only)
- `README.md`, `docs/SERVER-SETUP.md`
- Tests: `app/src/test/.../sync/*`, `app/src/test/.../data/*`,
  `app/src/androidTest/...`, new fixture under
  `app/src/test/resources/unison-output/`

## Risks and open questions

- **2.54.0 build recipe.** It is a fresh tag; `make NATIVE=true src` and the
  `lock.ml` O_EXCL sed patch may need adjustment (the patch fails loudly if
  `lock.ml`'s layout changed). Verify first; it is the main unknown.
- **2.54.0 output drift.** The parser is tuned to 2.53.8. Mitigated by the
  fallback + exit-code classification and one captured fixture; revisit if real
  runs parse poorly.
- **APK growth.** Two binaries × two ABIs roughly doubles native content. Kept
  as one universal APK for now; ABI splits are the follow-up.
- **2.51-compat mode confidence.** Coverage of 2.51.x via 2.53.8 is based on the
  2.53.8 RPC-v0 path and the 2.52.0 release notes; confirm empirically against a
  2.51.x server during implementation.
