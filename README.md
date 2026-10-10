# UnisonDroid

An Android client for [Unison](https://github.com/bcpierce00/unison) file
synchronization, syncing files between the device and remote hosts over SSH.

## Status

Functional for manual, one-tap sync to a Unison server. The app bundles both
Unison **2.54.0** and **2.53.8** binaries and each profile chooses which one to
run, so the same APK can sync with 2.52+ and older 2.51.x servers. Distributed
as signed APKs on GitHub Releases, with F-Droid inclusion pending.

## Building from source

Requirements:

- JDK 21
- Android SDK with platform 36 (set `sdk.dir` in `local.properties` or export
  `ANDROID_HOME` / `ANDROID_SDK_ROOT`)
- Android NDK `29.0.14206865` (only needed to build the bundled native binaries)
- A Linux host with OCaml build prerequisites (`gcc`, `make`, `curl`, `git`) to
  cross-compile Unison, and `perl` for the OpenSSL/OpenSSH build

The app embeds cross-compiled `unison` binaries plus the OpenSSH client tools
in `app/src/main/jniLibs/<abi>/` (`libunison_2_54_0.so`,
`libunison_2_53_8.so`, `libssh.so`, `libssh-keygen.so`, `libssh-keyscan.so`).
That directory is gitignored, so a clean checkout has to build it first:

```sh
# Build every bundled version (see native/unison-versions.txt) for both ABIs.
for v in $(grep -v '^#' native/unison-versions.txt); do
  UNISON_VERSION="$v" native/build-unison.sh arm64-v8a
  UNISON_VERSION="$v" native/build-unison.sh x86_64
done
native/build-openssh.sh arm64-v8a
native/build-openssh.sh x86_64
```

Each run installs the results into `app/src/main/jniLibs/<abi>/`. Then build the
app:

```sh
./gradlew :app:assembleDebug        # debug APK
./gradlew :app:testDebugUnitTest    # JVM unit tests (no native binary needed)
```

For instrumented tests, start an `x86_64` emulator and run:

```sh
./gradlew :app:connectedDebugAndroidTest
```

See [native/README.md](native/README.md) for the cross-compile pipeline details
and [docs/RELEASE.md](docs/RELEASE.md) for signing and release plumbing.

## Setting up the server

The phone syncs with a computer running `unison -socket`. See
[docs/SERVER-SETUP.md](docs/SERVER-SETUP.md) for installing and running the
server, authorizing the app's SSH key, and the systemd user unit.

## License

GPL-3.0-only. See [LICENSE](LICENSE).
