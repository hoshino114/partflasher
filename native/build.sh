#!/usr/bin/env bash
# Builds prebuilt JNI libraries. NDK path is local toolchain (not required for normal Gradle builds).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-$ROOT/.tooling/ndk/android-ndk-r28c}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
SRC="$ROOT/native/src/partflasher_jni.c"
OUT="$ROOT/app/src/main/jniLibs"

# Host test of the pure C SHA-256 implementation.
gcc -O2 -Wall -Wextra -I"$ROOT/native/src" \
    "$ROOT/native/src/sha256.c" "$ROOT/native/test_sha256.c" \
    -o "$ROOT/native/test_sha256"
"$ROOT/native/test_sha256"

for abi in arm64-v8a x86_64; do
  case "$abi" in
    arm64-v8a) cc="$TOOLCHAIN/aarch64-linux-android26-clang" ;;
    x86_64) cc="$TOOLCHAIN/x86_64-linux-android26-clang" ;;
  esac
  mkdir -p "$OUT/$abi"
  "$cc" -shared -fPIC -O2 -Wall -Wextra \
      -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \
      -I"$ROOT/native/src" \
      "$SRC" "$ROOT/native/src/sha256.c" \
      -o "$OUT/$abi/libpartflasher.so"
  "$TOOLCHAIN/llvm-readelf" -l "$OUT/$abi/libpartflasher.so" | grep -q 'LOAD.*0x4000' || {
    echo "ERROR: $abi library is not 16 KB page aligned" >&2; exit 1; }
  echo "built $abi/libpartflasher.so"
done
