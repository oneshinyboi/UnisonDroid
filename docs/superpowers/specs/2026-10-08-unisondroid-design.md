# UnisonDroid — Unison File Sync Client for Android

**Date:** 2026-10-08
**Status:** Approved design; implementation plan to follow
**License:** GPLv3 (matches upstream Unison)

## 1. Purpose

UnisonDroid is a free, open-source native Android app that lets anyone sync
folders between their phone and their own server running
[Unison](https://github.com/bcpierce003/unison), over SSH-key-authenticated,
encrypted connections.

V1 is manual sync only: create profiles, tap sync, watch the log, get a
summary. It is published on F-Droid and as signed APKs on GitHub Releases.

### Non-goals (v1)

- Scheduled or trigger-based background auto-sync
- Dedicated conflict-resolution UI (batch-mode duplicate-copy behavior is used)
- Plain-socket (unencrypted) transport
- armeabi-v7a support
- Google Play distribution

## 2. How it works (user's view)

1. Install the app; on the keys screen generate an Ed25519 key and copy the
   public key to the server's `authorized_keys`.
2. On the server, run `unison -socket 22333` (documented systemd user unit).
3. Create a profile: local folder (any path under `/storage`), remote root,
   host, SSH user/port, remote socket port, optional ignore patterns.
4. Tap the profile to sync. The run screen shows the live log and progress;
   the summary reports transferred / failed / conflicted files.

## 3. Architecture

```
Compose UI (profiles / editor / run / keys / about)
        | commands & StateFlow
SyncService (foreground service, one sync at a time)
        |
   +----+------------------+----------------------+
ProfileRepository        SshTunnel               UnisonProcess
(JSON, filesDir)         (sshj session +         (ProcessBuilder execs
KeyVault (Keystore-      local port forward)     nativeLibraryDir/
encrypted Ed25519)                                libunison.so, -batch,
HostKeyStore (TOFU)                               streams+parses output)
```

### Sync flow

Tap sync → SyncService starts a foreground notification → SshTunnel connects
(TOFU check on first use, hard failure on fingerprint change) and forwards
`127.0.0.1:<ephemeral>` → `server:127.0.0.1:<remoteSocketPort>` → the app
writes a generated `.prf` into the UNISON state directory → runs
`libunison.so <profile> -batch` with `UNISON=<filesDir>/unison-state`
(noBackup, so cloud backups never corrupt archives) → parsed stdout/stderr
events drive the run screen → summary on process exit → tunnel closed.

Only one sync runs at a time; queued requests start when the current run
finishes. Cancel kills the process and closes the tunnel.

### Remote side (documented, not built by us)

`unison -socket 22333` via a systemd user unit; docs ship in-app and in the
repo. The app never needs an `ssh` binary: sshj provides the authenticated
tunnel that unison's socket transport speaks through.

## 4. Data model (JSON via kotlinx-serialization, in `filesDir`)

- **Profile:** id, name, localRoot, remoteRoot, host, sshPort, user,
  remoteSocketPort, sshKeyId, ignorePatterns[], advancedPrefs (verbatim text
  appended to the generated profile), lastSyncedAt, lastResult.
- **SshKey:** id, name, publicKey, Keystore-encrypted private key material.
- **KnownHost:** host, port, fingerprint, approvedAt.

## 5. Unison integration

- **Version:** pin 2.53.x. Unison's protocol is strict across minor versions;
  mismatch errors from unison are parsed and surfaced with the detected server
  version ("server runs X, needs 2.53.x").
- **Generated `.prf`:** roots (local path and `socket://127.0.0.1:<ephemeral>`),
  Android-emulated-storage defaults (permissions not propagated, symlinks off,
  fat-style mtime tolerance), the profile's ignore patterns, then the advanced
  block verbatim so power users can override anything.
- **Conflicts:** batch mode → unison leaves duplicate copies; conflicts are
  reported in the summary and highlighted in the log.
- **Packaging:** unison cross-compiled with the NDK toolchain (OCaml 4.14,
  recipe adapted from Termux's build) and shipped as
  `jniLibs/{arm64-v8a,x86_64}/libunison.so`, which Android extracts and marks
  executable at install time.

## 6. Security

- Ed25519 keys generated in-app or OpenSSH-format keys imported; private keys
  encrypted at rest with an Android Keystore-wrapped key.
- TOFU host verification: explicit fingerprint approval on first connect,
  stored thereafter, hard failure on change.
- All traffic flows inside the SSH tunnel; no plaintext socket mode exists.

## 7. Error handling

| Failure | User-facing result |
| --- | --- |
| Version mismatch | "Server runs X, needs 2.53.x" |
| Tunnel refused | "Could not reach unison — is `unison -socket` running?" |
| SSH auth failure | "Key not in authorized_keys?" |
| Local permission error | "Check All Files Access grant" |
| Anything else | Raw log shown on run screen |

## 8. UI (single activity, Compose, Material 3)

1. **Profiles** — status chips (never synced / ok / warnings / failed),
   last-sync time; empty state is a getting-started card linking server setup.
2. **Profile editor** — form fields; local folder picked with a plain path
   browser over real paths; collapsible advanced block.
3. **Run screen** — live log stream, derived progress, cancel button, final
   summary.
4. **Keys** — generate/import, copy or share public key.
5. **About** — embedded unison version, server-setup docs, OSS licenses.

## 9. Testing

- **JVM unit tests:** prf generator, output parser, profile/key/host stores,
  tunnel seam (interface fakes).
- **Instrumented tests (x86_64 emulator):** a real local↔local sync through the
  embedded binary — end-to-end without SSH.

## 10. Build & release

- GitHub Actions cross-compiles unison per ABI, then Gradle assembles the APK.
- Tagged GitHub Releases with signed APKs; fastlane metadata prepared for the
  F-Droid inclusion MR.
- Application id: `io.unisondroid.app` (finalizable before F-Droid submission).

## 11. Risks

| Risk | Mitigation |
| --- | --- |
| OCaml/NDK cross-compile breakage | Pinned toolchains; native build is a separate reproducible CI job |
| FUSE/emulated-storage mtime quirks | fat-style defaults in generated profiles |
| Protocol strictness across unison minors | Clear version-mismatch errors; docs pin 2.53.x |
| Android 16 KB-page requirement | Only relevant if Play is added later; noted, not acted on |
