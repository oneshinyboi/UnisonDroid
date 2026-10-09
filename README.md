# UnisonDroid

An Android client for [Unison](https://github.com/bcpierce00/unison) file
synchronization, syncing files between the device and remote hosts over SSH.

## Status

Functional for manual, one-tap sync to a Unison 2.53.x server. Distributed as
signed APKs on GitHub Releases, with F-Droid inclusion pending.

## Building from source

Requirements:

- JDK 21
- Android SDK with platform 36 (set `sdk.dir` in `local.properties` or export
  `ANDROID_HOME` / `ANDROID_SDK_ROOT`)
- Android NDK `29.0.14206865` (only needed to build the bundled Unison binary)
- A Linux host with OCaml build prerequisites (`gcc`, `make`, `curl`, `git`) to
  cross-compile Unison

The app embeds a cross-compiled `unison` binary in
`app/src/main/jniLibs/<abi>/libunison.so`. That directory is gitignored, so a
clean checkout has to build it first:

```sh
native/build-unison.sh arm64-v8a
native/build-unison.sh x86_64
```

Each run installs the result into `app/src/main/jniLibs/<abi>/`. Then build the
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
