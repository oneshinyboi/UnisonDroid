# Releasing UnisonDroid

UnisonDroid ships from GitHub Actions:

- **`.github/workflows/ci.yml`** runs on every push and pull request. It
  cross-compiles `libunison.so` for `arm64-v8a` and `x86_64`, runs the JVM unit
  tests, assembles and lints the debug APK, and runs the instrumented end-to-end
  test on an `x86_64` Android 36 emulator.
- **`.github/workflows/release.yml`** runs on tags matching `v*`. It reuses the
  native build, signs the release APK with the repository's upload key, and
  attaches it to a GitHub Release.

## 1. One-time: create the upload keystore

APKs published on GitHub Releases and F-Droid must be signed with a stable key.
Losing it means you can never update the app under the same application id, so
keep a backup in a password manager.

Generate a keystore (RSA 4096) and answer the prompts:

```sh
keytool -genkeypair -v \
  -keystore release.jks \
  -alias unisondroid \
  -keyalg RSA -keysize 4096 -validity 10000
```

Encode it for CI and copy the output to your clipboard:

```sh
base64 -w0 release.jks
```

## 2. Configure repository secrets

In **Settings → Secrets and variables → Actions**, add:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | Base64 of `release.jks` (output of the command above) |
| `KEYSTORE_PASSWORD` | Keystore password chosen during generation |
| `KEY_ALIAS` | Key alias, e.g. `unisondroid` |
| `KEY_PASSWORD` | Password for that key (same as the keystore if you pressed Enter) |

`app/build.gradle.kts` reads `KEYSTORE_PATH` (a file path), `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, and `KEY_PASSWORD` from the environment. `release.yml` decodes
`KEYSTORE_BASE64` into a temporary file and sets `KEYSTORE_PATH`; local builds
without those variables produce an unsigned release APK.

## 3. Cut a release

```sh
git tag v0.1.0
git push origin v0.1.0
```

The release job runs the unit tests, builds and signs the APK, verifies the
signature with `apksigner`, and publishes a GitHub Release with
`unisondroid-v0.1.0-app-release.apk` attached. Follow SemVer and keep the tag in
step with `versionName` in `app/build.gradle.kts` (and bump `versionCode`).

Before tagging, bump the fastlane changelog: add
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` for the new build.

## 4. F-Droid inclusion

F-Droid builds from source and signs with its own key, so the upload keystore
above is only for GitHub Releases. Metadata under
`fastlane/metadata/android/en-US/` is consumed by F-Droid's `fdroiddata` build.

Submit an inclusion request (merge request / issue) to
`https://gitlab.com/fdroid/fdroiddata` using this checklist:

- [ ] App is fully free software: source available, GPL-3.0-only, no proprietary
      dependencies. (`LICENSE` contains the full GPLv3 text.)
- [ ] No Google Play Services, Firebase, crash reporting, ads, or analytics.
- [ ] Reproducible-ish build works in F-Droid's buildserver: pure Gradle, no
      prebuilt native libraries that are not built from source in CI. The
      `libunison.so` and `libssh*.so` binaries are **not** committed (see
      `.gitignore`); the buildserver runs `native/build-unison.sh` and
      `native/build-openssh.sh` for `arm64-v8a` and `x86_64`.
- [ ] `gradle/wrapper/gradle-wrapper.properties` pins a Gradle distribution with
      a `distributionSha256Sum`.
- [ ] `versionCode` / `versionName` are set and match the tag being released.
- [ ] An app icon is present (adaptive launcher icon).
- [ ] `fastlane/metadata/android/en-US/` has `title.txt`,
      `short_description.txt`, `full_description.txt`, and a matching
      `changelogs/<versionCode>.txt`.
- [ ] The application id (`io.unisondroid.app`) is final; F-Droid cannot change
      it later.
- [ ] A `Builds:` recipe for the metadata uses `subdir`/`gradle` correctly and
      declares `ndk: 29.0.14206865` plus the native steps (`build-unison.sh`
      and `build-openssh.sh`) if needed.
- [ ] Follow-up: confirm the acknowledged `AntiFeatures` (if any) and add
      `Unstable`/`Beta` tags only if appropriate.

After the MR is merged, F-Droid's build server builds and signs the APK; the
first build can take a while because it compiles OCaml and Unison from scratch.

## 5. Manual verification of a release APK

Install a published APK on a device or emulator:

```sh
adb install -r unisondroid-v0.1.0-app-release.apk
```

Then create a profile pointing at a server running `unison -socket 22333` and
run a sync. The About screen reports the bundled Unison version.

## 6. Debug vs release builds

Debug and release are two independent installs with different signatures, so
both can be installed side by side on one device:

| | Debug (local/dev) | Release (published) |
| --- | --- | --- |
| Application id | `io.unisondroid.app.dev` | `io.unisondroid.app` |
| Launcher label | UnisonDroid Dev | UnisonDroid |
| Version name | `<version>-dev` | `<version>` |
| Signing | default debug keystore (`~/.android/debug.keystore`) | upload keystore via `KEYSTORE_PATH` |

Debug builds are never signed with the release key. Because the application ids
differ, installing a dev build cannot clobber a published release (or the
reverse), and `./gradlew :app:connectedDebugAndroidTest` targets the `.dev`
package. A release build without `KEYSTORE_PATH` set is produced unsigned.

For an installable, release-signed build for local testing, run
`./gradlew :app:assembleRelease` with the signing environment set (section 2)
rather than re-signing a debug build.
