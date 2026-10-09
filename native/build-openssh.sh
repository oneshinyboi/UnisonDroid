#!/usr/bin/env bash
#
# Cross-compile OpenSSH for Android.
#
# Produces native/out/<abi>/{libssh.so,libssh-keygen.so,libssh-keyscan.so}
# for arm64-v8a and x86_64, and installs them into app/src/main/jniLibs.
#
# The pipeline is:
#   1. Cross-build OpenSSL ${OPENSSL_VERSION} (static libcrypto) with the
#      NDK's clang, using OpenSSL's android-* Configure targets.
#   2. Cross-build the OpenSSH client tools against it.
#   3. Strip the binaries and ship them as lib*.so so the APK packager
#      extracts them to nativeLibraryDir (same trick as libunison.so).
#
# Usage:
#   native/build-openssh.sh <arm64-v8a|x86_64>
#
# Environment overrides:
#   ANDROID_SDK_ROOT / ANDROID_HOME  Android SDK location (default ~/Android/Sdk)
#   OPENSSH_BUILD_DIR                scratch/cache directory (default native/.build-openssh)
#   FORCE=1                          rebuild even if outputs already exist
#   JOBS                             parallel make jobs (default: nproc)
#
set -euo pipefail

# --- Pins -------------------------------------------------------------------
OPENSSH_VERSION=10.2p1
OPENSSL_VERSION=3.5.9
NDK_VERSION=29.0.14206865
ANDROID_API=26
OPENSSH_URL="https://cdn.openbsd.org/pub/OpenBSD/OpenSSH/portable/openssh-${OPENSSH_VERSION}.tar.gz"
OPENSSL_URL="https://www.openssl.org/source/openssl-${OPENSSL_VERSION}.tar.gz"

# --- Paths ------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
NATIVE_DIR="$SCRIPT_DIR"
OUT_DIR="$NATIVE_DIR/out"
WORK_DIR="${OPENSSH_BUILD_DIR:-$NATIVE_DIR/.build-openssh}"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
NDK="$SDK/ndk/$NDK_VERSION"
TOOLCHAIN_BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
JOBS="${JOBS:-$(nproc)}"

log()  { printf '\033[1;34m[build-openssh]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[build-openssh] error:\033[0m %s\n' "$*" >&2; exit 1; }

# --- Argument / platform mapping -------------------------------------------
ABI="${1:-}"
case "$ABI" in
  arm64-v8a) TRIPLE=aarch64-linux-android; OPENSSL_TARGET=android-arm64 ;;
  x86_64)    TRIPLE=x86_64-linux-android;  OPENSSL_TARGET=android-x86_64 ;;
  *) die "usage: $(basename "$0") <arm64-v8a|x86_64>" ;;
esac

CC="$TOOLCHAIN_BIN/${TRIPLE}${ANDROID_API}-clang"
AR="$TOOLCHAIN_BIN/llvm-ar"
RANLIB="$TOOLCHAIN_BIN/llvm-ranlib"
STRIP="$TOOLCHAIN_BIN/llvm-strip"

SSL_PREFIX="$WORK_DIR/openssl-$TRIPLE"
OPENSSH_SRC="$WORK_DIR/openssh-$TRIPLE"
OUT_SSH="$OUT_DIR/$ABI/libssh.so"
OUT_KEYGEN="$OUT_DIR/$ABI/libssh-keygen.so"
OUT_KEYSCAN="$OUT_DIR/$ABI/libssh-keyscan.so"
JNI_DIR="$REPO_ROOT/app/src/main/jniLibs/$ABI"

install_to_jni() {
  mkdir -p "$JNI_DIR"
  cp "$OUT_SSH" "$OUT_KEYGEN" "$OUT_KEYSCAN" "$JNI_DIR/"
  log "installed 3 binaries into $JNI_DIR"
}

[ -x "$CC" ] || die "NDK $NDK_VERSION not found (looked for $CC); set ANDROID_SDK_ROOT"

if [ -f "$OUT_SSH" ] && [ "${FORCE:-0}" != "1" ]; then
  log "already built: $OUT_SSH (set FORCE=1 to rebuild)"
  install_to_jni
  exit 0
fi

mkdir -p "$WORK_DIR" "$OUT_DIR/$ABI"

download() { # url dest
  if [ ! -f "$2" ]; then
    log "downloading $1"
    curl -fsSL -o "$2" "$1"
  fi
}

# --- 1. OpenSSL (static libcrypto) ------------------------------------------
build_openssl() {
  if [ -f "$SSL_PREFIX/lib/libcrypto.a" ]; then
    log "OpenSSL already present at $SSL_PREFIX"
    return 0
  fi
  local tarball="$WORK_DIR/openssl-$OPENSSL_VERSION.tar.gz"
  download "$OPENSSL_URL" "$tarball"
  log "building OpenSSL $OPENSSL_VERSION for $TRIPLE"
  rm -rf "$WORK_DIR/openssl-$TRIPLE-src"
  tar xzf "$tarball" -C "$WORK_DIR"
  mv "$WORK_DIR/openssl-$OPENSSL_VERSION" "$WORK_DIR/openssl-$TRIPLE-src"
  (
    cd "$WORK_DIR/openssl-$TRIPLE-src"
    export PATH="$TOOLCHAIN_BIN:$PATH"
    ANDROID_NDK_ROOT="$NDK" ANDROID_API="$ANDROID_API" \
      ./Configure "$OPENSSL_TARGET" -static no-shared no-tests no-docs no-apps \
        --prefix="$SSL_PREFIX" --libdir=lib \
        > "$WORK_DIR/openssl-$TRIPLE-configure.log" 2>&1
    make -j"$JOBS" > "$WORK_DIR/openssl-$TRIPLE-make.log" 2>&1
    make install_sw > "$WORK_DIR/openssl-$TRIPLE-install.log" 2>&1
  )
  [ -f "$SSL_PREFIX/lib/libcrypto.a" ] || die "OpenSSL build failed (see $WORK_DIR/openssl-$TRIPLE-*.log)"
  log "OpenSSL built at $SSL_PREFIX"
}

# --- 2. OpenSSH client tools -------------------------------------------------
build_openssh() {
  if [ -x "$OPENSSH_SRC/ssh" ]; then
    log "OpenSSH already built at $OPENSSH_SRC"
    return 0
  fi
  local tarball="$WORK_DIR/openssh-$OPENSSH_VERSION.tar.gz"
  download "$OPENSSH_URL" "$tarball"
  log "building OpenSSH $OPENSSH_VERSION for $TRIPLE"
  rm -rf "$OPENSSH_SRC"
  tar xzf "$tarball" -C "$WORK_DIR"
  mv "$WORK_DIR/openssh-$OPENSSH_VERSION" "$OPENSSH_SRC"
  (
    cd "$OPENSSH_SRC"
    # Bionic ships bzero only as a macro (strings.h), so openssh's
    # function-pointer indirection in explicit_bzero.c cannot take its
    # address, and bionic has no explicit_bzero until API 28 (we target 26).
    # Reroute the volatile-pointer trick through memset, which is always
    # available as a real function.
    sed -i \
      -e 's/static void (\* volatile ssh_bzero)(void \*, size_t) = bzero;/static void ssh_memset0(void *p, int c, size_t n) { memset(p, c, n); }\nstatic void (* volatile ssh_memset)(void *, int, size_t) = ssh_memset0;/' \
      -e 's/ssh_bzero(p, n);/ssh_memset(p, 0, n);/' \
      openbsd-compat/explicit_bzero.c
    # getrrsetbyname needs glibc resolver internals (struct _res) bionic does
    # not have, but dns.c still references its API. Replace the implementation
    # with stubs: VerifyHostKeyDNS is never enabled by the app's ssh options.
    sed -i '1i #if !defined(__BIONIC__)' openbsd-compat/getrrsetbyname.c
    echo '#endif' >> openbsd-compat/getrrsetbyname.c
    cat >> openbsd-compat/getrrsetbyname.c <<'EOF'
#if defined(__BIONIC__)
#include "getrrsetbyname.h"
int getrrsetbyname(const char *hostname, unsigned int rdclass, unsigned int rdtype,
    unsigned int flags, struct rrsetinfo **res)
{
	(void)hostname; (void)rdclass; (void)rdtype; (void)flags; (void)res;
	return ERRSET_FAIL;
}
void freerrset(struct rrsetinfo *rrset) { (void)rrset; }
#endif
EOF
    # Bionic declares bzero with clang's overloadable attribute; configure's
    # declare-and-call probe fails against it and would make openssh redefine
    # the function. Seed the result: bionic has bzero.
    export ac_cv_func_bzero=yes
    # The client tools need no system accounting, zlib, or privilege-separation
    # setup; every runtime path (identity, known hosts, config) is passed
    # explicitly on the app's ssh command line.
    ./configure \
      --host="$TRIPLE" \
      --with-ssl-dir="$SSL_PREFIX" \
      --without-openssl-header-check \
      --without-zlib \
      --disable-lastlog --disable-utmp --disable-utmpx \
      --disable-wtmp --disable-wtmpx --disable-libutil \
      --disable-etc-default-login \
      --disable-pkcs11 \
      CC="$CC" AR="$AR" RANLIB="$RANLIB" STRIP="$STRIP" \
      CFLAGS="-O2 -fPIE" LDFLAGS="-pie" \
      CPPFLAGS="-DHAVE_ATTRIBUTE__SENTINEL__ -include strings.h" \
      > "$WORK_DIR/openssh-$TRIPLE-configure.log" 2>&1
    make -j"$JOBS" ssh ssh-keygen ssh-keyscan \
      > "$WORK_DIR/openssh-$TRIPLE-make.log" 2>&1
  )
  [ -x "$OPENSSH_SRC/ssh" ] || die "OpenSSH build failed (see $WORK_DIR/openssh-$TRIPLE-*.log)"
  log "OpenSSH built at $OPENSSH_SRC"
}

# --- 3. Ship -----------------------------------------------------------------
build_openssl
build_openssh
"$STRIP" "$OPENSSH_SRC/ssh" "$OPENSSH_SRC/ssh-keygen" "$OPENSSH_SRC/ssh-keyscan"
cp "$OPENSSH_SRC/ssh" "$OUT_SSH"
cp "$OPENSSH_SRC/ssh-keygen" "$OUT_KEYGEN"
cp "$OPENSSH_SRC/ssh-keyscan" "$OUT_KEYSCAN"
install_to_jni
log "done: $ABI"
