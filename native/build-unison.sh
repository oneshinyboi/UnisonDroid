#!/usr/bin/env bash
#
# Cross-compile Unison for Android.
#
# Produces native/out/<abi>/libunison.so for arm64-v8a and x86_64.
#
# The pipeline is:
#   1. Build a native (host) OCaml 4.14.x toolchain.
#   2. Cross-build an OCaml 4.14.x compiler that runs on the host but emits
#      Android code, using the NDK's clang as the target C compiler.
#   3. Cross-build Unison ${UNISON_VERSION} with NATIVE=true, statically linked
#      against bionic, and strip it.
#
# Usage:
#   native/build-unison.sh <arm64-v8a|x86_64>
#
# Environment overrides:
#   ANDROID_SDK_ROOT / ANDROID_HOME  Android SDK location (default ~/Android/Sdk)
#   UNISON_BUILD_DIR                 scratch/cache directory (default native/.build)
#   FORCE=1                          rebuild even if outputs already exist
#   JOBS                             parallel make jobs (default: nproc)
#
set -euo pipefail

# --- Pins -------------------------------------------------------------------
# Unison release tag pinned for the bundled native server binary.
# OutputParser mismatch markers and test fixtures are verified against this tag.
UNISON_VERSION=v2.53.8
OCAML_VERSION=4.14.2
NDK_VERSION=29.0.14206865
ANDROID_API=26
UNISON_REPO=https://github.com/bcpierce00/unison.git

# --- Paths ------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NATIVE_DIR="$SCRIPT_DIR"
OUT_DIR="$NATIVE_DIR/out"
WORK_DIR="${UNISON_BUILD_DIR:-$NATIVE_DIR/.build}"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
NDK="$SDK/ndk/$NDK_VERSION"
TOOLCHAIN_BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
HOST_OCAML_PREFIX="$WORK_DIR/host-ocaml"
JOBS="${JOBS:-$(nproc)}"

log()  { printf '\033[1;34m[build-unison]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[build-unison] error:\033[0m %s\n' "$*" >&2; exit 1; }

# --- Argument / platform mapping -------------------------------------------
ABI="${1:-}"
case "$ABI" in
  arm64-v8a) TRIPLE=aarch64-linux-android ;;
  x86_64)    TRIPLE=x86_64-linux-android ;;
  *) die "usage: $(basename "$0") <arm64-v8a|x86_64>" ;;
esac

CC="$TOOLCHAIN_BIN/${TRIPLE}${ANDROID_API}-clang"
AR="$TOOLCHAIN_BIN/llvm-ar"
RANLIB="$TOOLCHAIN_BIN/llvm-ranlib"
STRIP="$TOOLCHAIN_BIN/llvm-strip"
LD="$TOOLCHAIN_BIN/ld.lld"

CROSS_OCAML_PREFIX="$WORK_DIR/ocaml-$TRIPLE"
MIXED_BIN="$WORK_DIR/mixed-$TRIPLE/bin"
UNISON_SRC="$WORK_DIR/unison-$ABI"
OUT_BIN="$OUT_DIR/$ABI/libunison.so"

[ -x "$CC" ] || die "NDK $NDK_VERSION not found (looked for $CC); set ANDROID_SDK_ROOT"
[ -f "$TOOLCHAIN_BIN/ld.lld" ] || die "NDK toolchain incomplete: $TOOLCHAIN_BIN"

if [ -f "$OUT_BIN" ] && [ "${FORCE:-0}" != "1" ]; then
  log "already built: $OUT_BIN (set FORCE=1 to rebuild)"
  exit 0
fi

mkdir -p "$WORK_DIR" "$OUT_DIR/$ABI"

download() { # url dest
  [ -f "$2" ] && return 0
  log "downloading $1"
  curl -fsSL -o "$2" "$1"
}

# --- 1. Host OCaml ----------------------------------------------------------
build_host_ocaml() {
  if [ -x "$HOST_OCAML_PREFIX/bin/ocamlopt" ]; then
    log "host OCaml already present at $HOST_OCAML_PREFIX"
    return 0
  fi
  local tarball="$WORK_DIR/ocaml-$OCAML_VERSION.tar.gz"
  download "https://github.com/ocaml/ocaml/archive/refs/tags/$OCAML_VERSION.tar.gz" "$tarball"
  log "building host OCaml $OCAML_VERSION"
  rm -rf "$WORK_DIR/ocaml-host-src"
  tar xzf "$tarball" -C "$WORK_DIR"
  mv "$WORK_DIR/ocaml-$OCAML_VERSION" "$WORK_DIR/ocaml-host-src"
  (
    cd "$WORK_DIR/ocaml-host-src"
    ./configure --prefix="$HOST_OCAML_PREFIX" > "$WORK_DIR/host-configure.log" 2>&1
    make -j"$JOBS" world.opt > "$WORK_DIR/host-make.log" 2>&1
    make install > "$WORK_DIR/host-install.log" 2>&1
  )
}

# --- 2. Cross OCaml ---------------------------------------------------------
build_cross_ocaml() {
  if [ -x "$CROSS_OCAML_PREFIX/bin/ocamlopt" ] && [ -d "$MIXED_BIN" ]; then
    log "cross OCaml already present at $CROSS_OCAML_PREFIX"
    return 0
  fi
  local tarball="$WORK_DIR/ocaml-$OCAML_VERSION.tar.gz"
  download "https://github.com/ocaml/ocaml/archive/refs/tags/$OCAML_VERSION.tar.gz" "$tarball"
  log "building cross OCaml $OCAML_VERSION for $TRIPLE (API $ANDROID_API)"
  rm -rf "$WORK_DIR/ocaml-cross-$TRIPLE" "$CROSS_OCAML_PREFIX"
  tar xzf "$tarball" -C "$WORK_DIR"
  mv "$WORK_DIR/ocaml-$OCAML_VERSION" "$WORK_DIR/ocaml-cross-$TRIPLE"
  local src="$WORK_DIR/ocaml-cross-$TRIPLE"
  (
    cd "$src"
    # --host makes autoconf treat this as a cross build: target feature tests
    # fall back to defaults instead of running Android binaries on the host.
    ./configure --host="$TRIPLE" --prefix="$CROSS_OCAML_PREFIX" \
      CC="$CC" AR="$AR" RANLIB="$RANLIB" STRIP="$STRIP" \
      AS="$CC -c" ASPP="$CC -c" PARTIALLD="$LD -r" \
      --disable-debugger --disable-ocamldoc --disable-stdlib-manpages \
      --disable-instrumented-runtime > "$WORK_DIR/cross-configure-$TRIPLE.log" 2>&1

    # coldstart must run the freshly built stdlib with the build machine's
    # interpreter, not the Android ocamlrun it just produced.
    sed -i "s|OCAMLRUN='\$\$(ROOTDIR)/runtime/ocamlrun\$(EXE)'|OCAMLRUN='\$(OCAMLRUN)'|" Makefile

    local overrides=(
      OCAMLRUN="$HOST_OCAML_PREFIX/bin/ocamlrun"
      NEW_OCAMLRUN="$HOST_OCAML_PREFIX/bin/ocamlrun"
      OCAMLYACC="$HOST_OCAML_PREFIX/bin/ocamlyacc"
      SAK_CC=gcc
      "SAK_LINK=gcc -o \$(1) \$(2)"
    )
    export PATH="$HOST_OCAML_PREFIX/bin:$PATH"
    make -j"$JOBS" world "${overrides[@]}" > "$WORK_DIR/cross-world-$TRIPLE.log" 2>&1
    make -j"$JOBS" opt   "${overrides[@]}" > "$WORK_DIR/cross-opt-$TRIPLE.log" 2>&1
    make install         "${overrides[@]}" > "$WORK_DIR/cross-install-$TRIPLE.log" 2>&1
  )

  # The installed ocamlrun is an Android binary; the cross compiler runs on the
  # build machine, so swap in the host interpreter for the bytecode drivers.
  cp "$HOST_OCAML_PREFIX/bin/ocamlrun" "$CROSS_OCAML_PREFIX/bin/ocamlrun"

  # A mixed bin directory: host toplevel (Unison runs make_tools.ml with it)
  # plus the target compiler drivers. First on PATH during the Unison build.
  rm -rf "$MIXED_BIN"; mkdir -p "$MIXED_BIN"
  ln -sf "$HOST_OCAML_PREFIX/bin/ocaml" "$MIXED_BIN/ocaml"
  ln -sf "$HOST_OCAML_PREFIX/bin/ocamlrun" "$MIXED_BIN/ocamlrun"
  for t in ocamlc ocamlopt ocamlmklib ocamldep ocamllex ocamlyacc ocamlobjinfo ocamlprof; do
    ln -sf "$CROSS_OCAML_PREFIX/bin/$t" "$MIXED_BIN/$t"
  done
}

# --- 3. Unison --------------------------------------------------------------
build_unison() {
  log "building Unison $UNISON_VERSION for $ABI"
  rm -rf "$UNISON_SRC"
  git clone --depth 1 --branch "$UNISON_VERSION" "$UNISON_REPO" "$UNISON_SRC" \
    > "$WORK_DIR/unison-clone-$ABI.log" 2>&1
  (
    cd "$UNISON_SRC"
    export PATH="$MIXED_BIN:/usr/bin:/bin"
    # -lutil does not exist on bionic (openpty lives in libc); link statically
    # and pull in libdl for the dl* symbols in the OCaml unix runtime.
    make NATIVE=true src -j"$JOBS" 'CLIBS=-cclib -static -cclib -ldl' \
      > "$WORK_DIR/unison-make-$ABI.log" 2>&1
  )
  [ -f "$UNISON_SRC/src/unison" ] || die "Unison build failed; see $WORK_DIR/unison-make-$ABI.log"
  cp "$UNISON_SRC/src/unison" "$OUT_BIN"
  "$STRIP" "$OUT_BIN"
  log "wrote $OUT_BIN ($(du -h "$OUT_BIN" | cut -f1))"
  "$STRIP" --version >/dev/null 2>&1 || true
}

build_host_ocaml
build_cross_ocaml
build_unison

log "done. Copy into the app with:"
log "  mkdir -p app/src/main/jniLibs/$ABI && cp native/out/$ABI/libunison.so app/src/main/jniLibs/$ABI/"
