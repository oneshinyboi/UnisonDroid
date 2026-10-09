# OpenSSH Transport Design

Replace the in-process sshj transport with bundled OpenSSH, driven by unison's
native `ssh://` root support. Deletes the loopback relay and everything that
exists only to bridge sshj to the bundled unison binary.

## Context

Two production bugs shipped in the relay architecture (v0.3.0-v0.3.3), both
verified on a physical device against a real server:

1. Sequential 5s pump-join timeouts in `SshjTunnel.startExecRelay` killed any
   transfer longer than ~15s ("Lost connection with the server"). Fixed by
   removing the timeouts.
2. `SyncEngine`'s tunnel watcher raced the local unison process on normal
   completion (remote unison exits first, closing the relay while the local
   process still flushes archives), reporting failure after a successful,
   byte-perfect transfer. Fixed with a grace period.

Both bugs lived in code that exists only because sshj runs in-process and needs
a loopback TCP bridge to the bundled unison binary. sshj itself negotiated
`curve25519-sha256`/`ssh-ed25519`/`chacha20-poly1305` against OpenSSH 10.2
correctly. Removing the bridge removes the bug class; OpenSSH is the transport
every desktop unison user already runs.

## Goals

- Unison connects via its native `ssh://` root and a bundled `ssh` binary.
- Delete the relay, tunnel lifecycle, watcher race, and the sshj/bcprov
  dependencies.
- Preserve: KeyVault (encrypted at rest), TOFU host-key prompting with
  fingerprint display, profile JSON compatibility, current error taxonomy.
- SSH_EXEC transport only; drop SOCKET (`unison -socket` daemon) support.

## Non-goals

- ssh-agent support, hardware key auth, encrypted key files in the vault.
- Port forwarding or any `ssh -L` features.
- Supporting remote roots on non-POSIX servers.

## Architecture

```
SyncEngine
  ├─ SshTool (interface; shells out to bundled binaries)
  │    ├─ scanHostKeys(host, port)        -> fingerprints (ssh-keyscan + ssh-keygen -lf)
  │    └─ generateKey() / derivePublicKey(pem) (ssh-keygen)
  ├─ HostKeyGate: proactive TOFU before the sync (unknown host -> prompt)
  └─ UnisonRunner (unchanged) runs: unison <profile> -batch
        └─ unison spawns: <libssh.so> ... user@host unison -server
```

No tunnel object, no loopback sockets, no relay threads. One process lifecycle:
unison's. ssh is its child; the existing `killDescendantsBestEffort` in
`RunningProcess.kill()` already kills the tree.

## Components

### 1. Native build: `native/build-openssh.sh`

Cross-compile OpenSSH portable (pin `OPENSSH_VERSION=10.2p1`, the line
validated against during this design's device testing) for arm64-v8a and x86_64 using the existing NDK pipeline from
`build-unison.sh` (NDK 29, API 26, clang, static bionic). Link OpenSSL
statically (pin latest 3.x LTS). Outputs `native/out/<abi>/libssh.so` and
`libssh-keyscan.so` (ssh-keygen is produced by the same build and also shipped
as `libssh-keygen.so`; used by `SshTool`).

Follows the established trick: executables named `lib*.so` in
`app/src/main/jniLibs/` are extracted to `nativeLibraryDir` and executed via
absolute path.

Size: ~2-3MB per ABI; offset by dropping sshj + bcprov dex.

### 2. Runtime SSH layout

`no_backup/ssh/` (0700):
- `known_hosts` (0600) — written by the TOFU gate, read by ssh via
  `-o UserKnownHostsFile=`.
- `<keyId>` key files (0600) — decrypted from KeyVault immediately before the
  run, deleted in `finally`.

All path-dependent behavior is passed explicitly on the command line
(`-i`, `-o UserKnownHostsFile=`, `-o IdentitiesOnly=yes`, `-o BatchMode=yes`)
so ssh never consults `$HOME`, `/etc/ssh`, or passwd.

### 3. Profile and prf generation

`PrfGenerator.generate(profile, sshArgs)` emits:

```
root = <localRoot>
root = ssh://<user>@<host><remoteRoot>
ssh = <nativeLibraryDir>/libssh.so -i <keyfile> -o UserKnownHostsFile=<path> -o StrictHostKeyChecking=yes -o BatchMode=yes -o IdentitiesOnly=yes -o LogLevel=ERROR -p <sshPort>
perms = 0
links = false
fat = true
servercmd = <serverCommand>    (only when it differs from "unison")
<ignore = Path ...>
<advancedPrefs>
```

The sshPort lives only in the `-p` flag (keeping it out of the root avoids
unison double-passing the port). The socket root, `localSocketPort`
parameter, and `remoteSocketPort` are removed from generation. `Profile`
keeps `transport`/`remoteSocketPort` fields (deserialized and ignored) so
existing profile JSON and archives stay valid. Profile edit UI loses the
transport toggle and socket port field.

### 4. TOFU host-key gate (pre-sync phase)

Changed-host detection is delegated to ssh itself: we always connect with
`StrictHostKeyChecking=yes` against the app's `known_hosts`, so a changed key
fails the connection and is mapped to a TUNNEL failure. Scanning is only
needed for first contact. Before starting unison:

- `HostKeyStore.known(host, port)` is null -> scan with
  `libssh-keyscan.so -T 5 -t ed25519,ecdsa-sha2-nistp256,rsa-sha2-256,rsa-sha2-512`,
  compute fingerprints with `libssh-keygen.so -lf`, emit `AwaitingHostKey`
  (reuse existing state and UI). On approve: write the `known_hosts` entry
  and `HostKeyStore`. On decline/timeout: fail with the existing semantics
  (AUTH / TUNNEL, 300s decision timeout).
- Known host -> proceed directly; no per-sync scan cost.

The mid-handshake sshj callback is gone; TOFU happens before unison starts.

### 5. SyncEngine

- Delete `SshTunnel`, `SshjTunnel`, `SshCrypto`, `TunnelSpec`, `TunnelHandle`,
  the relay, the watcher, and the grace-period race fix.
- Order: binary check -> TOFU gate -> write prf -> run unison -> parse ->
  finalize. The `settled`/watcher/`awaitClosed` machinery collapses to
  awaiting the single process exit code.
- Cancel: `proc.kill()` (tree-kill covers ssh).
- `SshCrypto`'s provider install is deleted with the last JCE consumer.

### 6. Keys (`SshTool` + KeyVault)

- `KeyVault.generate`: replace BouncyCastle `Ed25519PrivateKeyParameters` with
  `ssh-keygen -t ed25519 -N "" -C <name> -f <tmp>`; import the PEM and public
  line exactly as today.
- `KeyVault.importOpenSsh`: replace `SSHClient().loadKeys` validation with
  `ssh-keygen -y -f <tmp>` (derives the public key; rejects malformed keys).
- Both call sites go through `SshTool` so unit tests fake the binaries.

### 7. Error mapping

ssh failures surface through unison's output/stderr and exit codes:
- "Permission denied (publickey...)" -> AUTH
- "Host key verification failed" / "REMOTE HOST IDENTIFICATION HAS CHANGED" -> TUNNEL
- ssh exit 255 with connect errors ("Connection refused/timed out", "No route")
  -> TUNNEL
- Everything else keeps the current taxonomy (EXIT, VERSION,
  LOCAL_PERMISSIONS, BINARY_MISSING, CANCELLED).

### 8. Testing

- Unit (JVM): `SshTool` fakes; TOFU gate state transitions; PrfGenerator
  output (exact ssh line, ssh:// root quoting); error-mapping table.
- Delete `SshTunnelTest` and the Apache MINA sshd test harness.
- Instrumented (androidTest): real binaries on device — `ssh -V` smoke test,
  keygen/derive round-trip, and the real-server e2e profile
  (`E2eProvisioningTest` pattern) gated on external config like today.

## Model/UI changes

- `Profile`: fields kept for deserialization; `Transport` enum retained but
  inert (all values treated as SSH_EXEC).
- `ProfileEditorScreen`: remove transport selector and socket-port field.
- `KeyVault` public API unchanged.

## Risks and mitigations

- OpenSSH-on-Android quirks (getpwuid/passwd, config paths): all identity
  paths passed via flags; spike milestone runs the binaries on-device before
  any app work. Termux ships the same pattern at scale.
- unison's ssh invocation goes through `/system/bin/sh` quoting: this is the
  desktop path; verified against a fish remote shell.
- Remote `unison` resolution under non-interactive fish: verified working
  (serverCommand = "unison").
- Larger native surface to keep patched: pin versions; CI builds both ABIs.

## Milestones

1. Spike: `build-openssh.sh` produces binaries; on-device `libssh.so -V`,
   manual `ssh` to the test server works from the app uid.
2. `SshTool` + KeyVault keygen/import migration; drop bcprov.
3. PrfGenerator ssh root + SyncEngine rewrite; delete tunnel layer.
4. TOFU gate + error mapping.
5. UI trim, dependency removal, test migration, e2e on device.

## Cleanup

Remove: sshj + bcprov from the version catalog and build files,
`SshCrypto.kt`, `SshTunnel.kt`, `SshTunnelTest.kt`, sshd-core test dependency,
`Transport` UI wiring, `remoteSocketPort` UI wiring.
