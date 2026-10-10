# Native builds

`build-unison.sh` cross-compiles the [Unison](https://github.com/bcpierce00/unison)
file synchronizer for Android and writes static binaries that the app ships as
`libunison_<ver>.so`, one per bundled Unison version.

The bundled versions are listed in `native/unison-versions.txt` (newest first),
the single source of truth shared by this build script, CI, and the app's
`UnisonInfo.BUNDLED`.

`build-openssh.sh` cross-compiles OpenSSH (the `ssh`, `ssh-keygen` and
`ssh-keyscan` client tools) against a statically linked OpenSSL and ships them
as `libssh.so`, `libssh-keygen.so` and `libssh-keyscan.so`. The app uses these
for unison's native `ssh://` transport.

## Output

For each ABI the script produces one binary per version in
`native/unison-versions.txt`:

```
native/out/<abi>/libunison_2_54_0.so   # statically linked, stripped, < 20 MiB
native/out/<abi>/libunison_2_53_8.so
```

Supported ABIs: `arm64-v8a`, `x86_64`.

The file name is derived from the release tag: `v2.54.0` becomes
`libunison_2_54_0.so` (drop the `v`, replace `.` with `_`). Each file is really
an executable, but it must be named `lib*.so` so that it is packaged into the
APK and unpacked into `ApplicationInfo.nativeLibraryDir`, where `BinaryLocator`
(`app/.../sync/UnisonRunner.kt`) looks for it.

## Prerequisites

- Linux x86_64 host (macOS is untested).
- Android SDK with the NDK installed. The build pins NDK **29.0.14206865**.
  Set `ANDROID_SDK_ROOT` (or `ANDROID_HOME`) if it is not at `~/Android/Sdk`.
- `git`, `curl`, `make`, a C toolchain, and ~4 GiB of RAM.
- An OCaml toolchain is **not** required: the script builds OCaml 4.14.2 for the
  host and then cross-builds it for Android from source.

## Usage

```sh
# Build both ABIs for every bundled version (first run compiles OCaml twice,
# expect ~20-30 min per ABI). Pass UNISON_VERSION to pick a tag.
for v in $(grep -v '^#' native/unison-versions.txt); do
  UNISON_VERSION="$v" native/build-unison.sh arm64-v8a
  UNISON_VERSION="$v" native/build-unison.sh x86_64
done
```

Useful environment variables:

| Variable           | Meaning                                              |
| ------------------ | ---------------------------------------------------- |
| `ANDROID_SDK_ROOT` | Android SDK location (default `~/Android/Sdk`)       |
| `UNISON_VERSION`   | Unison release tag to build (default: first tag in `native/unison-versions.txt`) |
| `UNISON_BUILD_DIR` | Scratch/cache directory (default `native/.build`)    |
| `FORCE=1`          | Rebuild even if the output already exists            |
| `JOBS`             | Parallel `make` jobs (default: `nproc`)              |

Other pins are at the top of the script: OCaml `4.14.2`, NDK `29.0.14206865`,
`ANDROID_API=26` (matches the app's `minSdk`).

## Adding a bundled version

1. Append its release tag (newest first) to `native/unison-versions.txt`.
2. Add a matching `BundledUnison` entry to `UnisonInfo.BUNDLED` in the app; a
   parity test asserts the two lists agree.
3. Rebuild every ABI for the new tag (see the loop above). `build-unison.sh`
   names the output `libunison_<ver>.so`, so no script change is needed.

The CI matrix is derived from `native/unison-versions.txt` (the workflow reads
the file and expands the `unison` matrix from it), so appending the tag is what
adds the new version to the native build matrix.

## Copying the libraries into the app

Gradle only packages native libraries found under `app/src/main/jniLibs/<abi>/`.
`build-unison.sh` writes its result there automatically (and re-copies it on
subsequent no-op runs), so no manual step is required:

```sh
native/build-unison.sh arm64-v8a   # -> app/src/main/jniLibs/arm64-v8a/libunison_2_53_8.so
native/build-unison.sh x86_64      # -> app/src/main/jniLibs/x86_64/libunison_2_53_8.so
```

The directory is gitignored; to copy from an already-built `native/out/` by
hand:

```sh
for abi in arm64-v8a x86_64; do
  mkdir -p app/src/main/jniLibs/$abi
  cp native/out/$abi/libunison_*.so app/src/main/jniLibs/$abi/
  cp native/out/$abi/libssh*.so app/src/main/jniLibs/$abi/
done
```

Then build and install the app:

```sh
./gradlew :app:assembleDebug
```

When the binaries are present, `BinaryLocator.locate(fileName)` returns
`BinaryStatus.Available` and the About screen lists the bundled versions.

## OpenSSH (`build-openssh.sh`)

For each ABI the script produces:

```
native/out/<abi>/libssh.so          # ssh client (unison's remote shell)
native/out/<abi>/libssh-keygen.so   # ssh-keygen (generation, -y, -lf)
native/out/<abi>/libssh-keyscan.so  # ssh-keyscan (TOFU host-key discovery)
```

Supported ABIs: `arm64-v8a`, `x86_64`. Usage:

```sh
native/build-openssh.sh arm64-v8a
native/build-openssh.sh x86_64
```

Useful environment variables:

| Variable             | Meaning                                                    |
| -------------------- | ---------------------------------------------------------- |
| `ANDROID_SDK_ROOT`   | Android SDK location (default `~/Android/Sdk`)             |
| `OPENSSH_BUILD_DIR`  | Scratch/cache directory (default `native/.build/openssh`)  |
| `FORCE=1`            | Rebuild even if the output already exists                  |
| `JOBS`               | Parallel `make` jobs (default: `nproc`)                    |

Pinned versions are at the top of the script: OpenSSH `10.2p1`, OpenSSL
`3.5.9`, NDK `29.0.14206865`, `ANDROID_API=26` (matches the app's `minSdk`).
The script writes the three binaries into `app/src/main/jniLibs/<abi>/` (and
re-copies them on no-op runs), alongside the `libunison_<ver>.so` binaries.

Building against bionic needs a few adaptations, all in the script:

- Define `HAVE_ATTRIBUTE__SENTINEL__` (cross-configure leaves it unset, so
  `defines.h` would stub `__sentinel__` empty and break bionic's `unistd.h`).
- Seed `ac_cv_func_bzero=yes` and force-include `<strings.h>` (bionic declares
  `bzero` only there and as an overloadable macro).
- Reroute `explicit_bzero`'s volatile-pointer trick through a `memset` wrapper
  (bionic `bzero`/`memset` are macros / overloadable, not addressable).
- Stub `getrrsetbyname` (bionic has no resolver internals; DNS host-key
  verification is never enabled).

## How it works

1. **Host OCaml** — OCaml 4.14.2 is configured and built normally
   (`make world.opt`) and installed under `native/.build/host-ocaml`. This is
   the interpreter/build toolchain the cross build runs on.
2. **Cross OCaml** — OCaml is configured with `--host=<triple>`, which makes
   autoconf take the cross path (feature tests fall back to defaults instead of
   executing Android binaries). The NDK clang (`<triple><api>-clang`), `llvm-ar`,
   `llvm-ranlib`, `llvm-strip` and `ld.lld` are used as the target tools.
   `coldstart`, `sak` and `ocamlyacc` are forced to run with the *host* tools so
   the bootstrap never tries to execute Android code. The result is a compiler
   that runs on the build machine but emits Android code; its installed
   `ocamlrun` is replaced with the host interpreter and the compiler drivers are
   exposed through a mixed `bin/` directory.
3. **Unison** — each tag in `native/unison-versions.txt` is built with
   `make NATIVE=true src` against the cross compiler, passing
   `CLIBS=-cclib -static -cclib -ldl` to link statically against bionic
   (`-lutil` does not exist on Android; the `dl*` symbols live in `libdl`). The
   result is stripped with `llvm-strip`.

## Notes

- The binaries are statically linked, so no `libc++_shared.so` or other runtime
  libraries need to be bundled.
- `make`'s default target also builds the man page, which runs the freshly built
  Android binary; the script builds only the `src` target to avoid that.
- This is the pipeline Task 16 wires into CI; keep the pins above in sync with
  `native/unison-versions.txt` and the app's `UnisonInfo.BUNDLED` / `DEFAULT`.
