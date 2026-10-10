# Complexity Offload and Background Sync Design

Continue the pattern established by the OpenSSH transport migration: delete
hand-rolled code that exists only to bridge to a bundled tool, and let the
upstream tool own the behavior. This design removes three more pockets of
bespoke code (host-key storage, ssh option assembly, key-at-rest crypto) and
adds per-profile scheduled sync on a single execution path shared by manual and
background runs.

## Context

The OpenSSH transport migration (`2026-10-09-openssh-transport-design.md`)
replaced an in-process sshj relay with bundled OpenSSH driven by Unison's native
`ssh://` roots, deleting a whole class of lifecycle bugs. The remaining
hand-rolled surfaces are:

1. Host-key trust is stored twice: the `known_hosts` file that `ssh` enforces,
   and a parallel `HostKeyStore` JSON store used only by the TOFU gate.
2. `PrfGenerator` assembles ssh's options into a long `sshargs` string.
3. `KeystoreAesCipher` implements AES-GCM envelope handling by hand.
4. There is no scheduled sync, and the foreground `SyncService` is a ~300-line
   bespoke lifecycle that would be duplicated to add one.

This app has no users and no compatibility constraints: stored formats may
change without migration.

## Goals

- A single execution path: manual "Sync now" and scheduled background sync both
  run through one `SyncWorker` driving one `SyncEngine`.
- Delete `SyncService` and its state-machine/notification lifecycle.
- OpenSSH's `known_hosts` is the only host-key trust store; `ssh-keygen -F`
  answers trust queries.
- `ssh_config` (`-F`) owns ssh option assembly.
- Tink owns authenticated encryption at rest for private keys.
- Per-profile scheduled sync with an interval, a mobile-data option, and
  notifications only when action is needed.

## Non-goals

- Reducing native cross-compile maintenance (a separate future effort).
- Charging-only constraints, multiple/cron schedules, or sub-15-minute
  intervals.
- Migration of previously stored keys or profiles; format changes are accepted.
- Replacing `OutputParser` (Unison 2.53.8 has no structured output).
- Introducing a DI library; `ServiceLocator` stays.

## Architecture

```
RunScreen "Sync now" ─┐
                      ├─ SyncScheduler ─> SyncWorker ─> SyncEngine (StateFlow) ─> UI / notifications
periodic per profile ─┘                        │
                                        setForeground (dataSync)
```

- `SyncScheduler` maps the profile list and app settings onto WorkManager work.
- `SyncWorker` is the only component that executes a sync. It promotes to a
  `dataSync` foreground service for the duration and returns a `Result`.
- `SyncEngine` remains the single source of truth, exposing the same
  `StateFlow<SyncState>` the UI already observes.

## Components

### 1. Sync execution model

- Delete `SyncService` and its manifest entry. `SyncWorker` replaces it.
- `SyncWorker : CoroutineWorker`:
  - Input data: `profileId` (String) and `mode` (`INTERACTIVE` | `UNATTENDED`).
  - Reads `SyncEngine` from `ServiceLocator` (no custom `WorkerFactory` needed;
    the default factory constructs the worker from `(Context, WorkerParameters)`
    and the worker looks up the engine itself).
  - Calls `setForeground(...)` at start so long runs survive backgrounding.
  - Maps `SyncOutcome` to `Result`: `COMPLETED`/`FAILED`/`SKIPPED_UNTRUSTED` →
    `success()`; `BUSY` → `retry()` (scheduled only; manual `BUSY` is a no-op).
  - On `onStopped`, cancels the engine run.
- `SyncScheduler`:
  - `syncNow(profileId)` → `enqueueUniqueWork("sync-<id>-now", KEEP,
    OneTimeWorkRequest<SyncWorker>)` with `INTERACTIVE`.
  - `reconcile(profiles)` → for each profile, if `autoSyncEnabled`
    `enqueueUniquePeriodicWork("sync-<id>", UPDATE, …)` with `UNATTENDED` and the
    current network constraint; otherwise `cancelUniqueWork("sync-<id>")`.
  - Called after profile save/delete, after the mobile-data setting changes, and
    at app start. No `BOOT_COMPLETED` receiver: WorkManager persists periodic
    work across reboot.

### 2. SyncEngine mode and outcome

`requestSync` gains a mode and a richer result:

```kotlin
enum class SyncMode { INTERACTIVE, UNATTENDED }

enum class SyncOutcome { COMPLETED, FAILED, SKIPPED_UNTRUSTED, BUSY }

suspend fun requestSync(profileId: String, mode: SyncMode): SyncOutcome
```

- `INTERACTIVE` — current behavior: an unknown host key emits
  `SyncState.AwaitingHostKey` and awaits `respondHostKey`.
- `UNATTENDED` — never prompts. An unknown host key returns
  `SKIPPED_UNTRUSTED` without entering `AwaitingHostKey`. Every other failure is
  unchanged.
- The existing `syncMutex` still serializes runs; a run that cannot take the
  lock returns `BUSY`.

### 3. Host-key trust unification

Delete `HostKeyStore.kt`, the `KnownHost` model, `TofuVerdict`, and the
`known_hosts` JSON store. The `known_hosts` file that `ssh` already enforces is
authoritative.

- `SshTool` gains `isHostKnown(host, port, knownHosts: File): Boolean`,
  implemented as `ssh-keygen -F "[host]:port" -f <knownHosts>` (exit 0 =
  present). Fingerprints keep using `ssh-keygen -lf`; a `forgetHost` via
  `ssh-keygen -R` is added for a future "remove trust" action.
- `HostKeyGate` takes the `known_hosts` file instead of `HostKeyStore`:
  - `isHostKnown` → `Trusted`.
  - Otherwise scan (`ssh-keyscan`) and fingerprint (`-lf`), then:
    - `INTERACTIVE` → prompt; on approval append the scanned line to
      `known_hosts`.
    - `UNATTENDED` → `Untrusted` (mapped to `SKIPPED_UNTRUSTED`).
  - Changed keys remain ssh's job (`StrictHostKeyChecking=yes` → `TUNNEL`).
- `SyncEngine` drops its `hostKeys` dependency.

### 4. ssh_config

`PrfGenerator` stops assembling the `-o` flag list. A small writer emits a
config next to the run's key:

```
Host *
  IdentityFile <keyFile>
  UserKnownHostsFile <knownHosts>
  GlobalKnownHostsFile /dev/null
  StrictHostKeyChecking yes
  BatchMode yes
  IdentitiesOnly yes
  LogLevel ERROR
  Port <sshPort>
```

The profile's `sshargs` becomes `-F <configPath>`. `-F` suppresses the
user/system ssh config, and `GlobalKnownHostsFile /dev/null` also suppresses
`/etc/ssh/ssh_known_hosts` (which the current `-o` flags leave enabled). The
port lives only here, keeping it out of the `ssh://` root.

### 5. Key-at-rest crypto via Tink

Replace `KeystoreAesCipher` with a Tink-backed `KeyCipher`:

- `AndroidKeysetManager` with an `AES256_GCM` AEAD template, master key URI
  `android-keystore://unisondroid-master`, keyset persisted in `filesDir`.
- `encrypt`/`decrypt` use the `Aead` primitive; `SshKey.encryptedPrivateBase64`
  holds the Tink ciphertext.
- `KeyCipher` remains an interface so tests fake it; `KeyVault` is unchanged.
- No migration: previously stored keys are simply re-added. Tink was chosen over
  deprecated `androidx.security:security-crypto`.

### 6. Settings

- New `AppSettings(syncOnMobileData: Boolean = true)` serialized via `JsonStore`
  behind a `SettingsRepository`.
- `syncOnMobileData = true` → `NetworkType.CONNECTED`; `false` →
  `NetworkType.UNMETERED`.

### 7. Notifications

- The worker's `setForeground` notification is the running indicator (reuse the
  `unison_sync` channel).
- Terminal notifications: `UNATTENDED` posts on `FAILED`, and on
  `SKIPPED_UNTRUSTED` ("open the app to approve the host key"); success is
  silent. `INTERACTIVE` posts nothing at the end.

### 8. Permissions

- Manifest: add `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
  (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` and
  `POST_NOTIFICATIONS` already exist).
- When auto-sync is first enabled, offer the battery-optimization exemption
  (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) and request
  `POST_NOTIFICATIONS` if not granted.
- Android 15 caps `dataSync` foreground services at 6 h/24 h; a run that hits
  the cap ends and the worker returns `retry()`.

### 9. UI

- `ProfileEditorScreen`: "Sync automatically" switch + interval picker (15 min /
  30 min / 1 h / 3 h / 6 h / 12 h / 24 h), with the exemption nudge when
  enabled.
- New Settings screen for "Don't sync on mobile data", added to `Navigation.kt`.

## Model and storage changes

- `Profile` gains `autoSyncEnabled: Boolean = false` and
  `autoSyncIntervalMinutes: Int = 60` (clamped to 15 min).
- Remove `KnownHost`; hosts live only in `known_hosts`.
- Add `AppSettings` (JSON via `JsonStore`).
- Existing `ssh_keys.json` ciphertext becomes unreadable; user re-adds keys.

## Testing

- Unit:
  - `SyncEngine` `UNATTENDED`: unknown host → `SKIPPED_UNTRUSTED` with no
    `AwaitingHostKey`; ordinary failure → `FAILED`.
  - `HostKeyGate`: trusted via faked `isHostKnown`; no prompt invoked in
    `UNATTENDED`.
  - ssh-config writer / `PrfGenerator`: exact text; `sshargs` contains only `-F`.
  - `SyncScheduler`: periodic work enqueued/cancelled per profile and
    constraints, via `WorkManagerTestInitHelper` + `TestDriver`.
  - `SyncWorker`: outcome/notification mapping via `TestListenableWorkerBuilder`.
  - `KeyCipher`: round-trip through the interface fake.
- Instrumented: real `libssh-keygen.so -F`; the existing real-server e2e.
- Remove/replace: `HostKeyStoreTest`, `SyncServiceTest`; update `HostKeyGateTest`,
  `SyncEngineTest`, `ProfileRepositoryTest`.
- Tink's Android Keystore keyset does not run under Robolectric, so the
  real-keystore test stays instrumented and unit tests use the `KeyCipher` fake.

## Risks and mitigations

- Starting a foreground service from a background worker is restricted on
  Android 14+ — mitigated by the battery-optimization exemption prompt; verify
  on-device.
- Android 15 `dataSync` cap — handle `onTimeout`/failure as retry.
- WorkManager test harness adds setup; keep `SyncScheduler` behind a thin
  interface so the engine is unaffected.
- `ssh-keygen -F` matching — we write plain `[host]:port` lines, which `-F`
  finds.

## Milestones

1. Refactors: host-key unification, ssh_config, Tink (each independently
   testable; no behavior change to a manual sync).
2. Execution model: `SyncWorker` + `SyncScheduler`, delete `SyncService`.
3. Mode + outcome: `INTERACTIVE`/`UNATTENDED`, `SKIPPED_UNTRUSTED`.
4. Settings, notifications, permissions.
5. UI: profile schedule controls + Settings screen.

## Cleanup

Remove: `SyncService.kt`, `HostKeyStore.kt`, `KeystoreAesCipher`, `KnownHost`,
`TofuVerdict`, `HostKeyStoreTest`, `SyncServiceTest`, `Busy`/foreground-service
wiring in the UI that pointed at `SyncService`; update the manifest.
