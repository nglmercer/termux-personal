#!/usr/bin/env bash
# Compile-time checks against Android's kernel UAPI; no emulator or device is used.
set -euo pipefail
repo_dir=$(cd "$(dirname "$0")/.." && pwd)
ndk_root=${1:?Usage: test-glibc-kernel-abi.sh /absolute/path/to/android-ndk}
clang="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
[[ -x "$clang" ]] || { printf '%s\n' 'NDK clang not found' >&2; exit 1; }
for target in aarch64-linux-android21 armv7a-linux-androideabi21 i686-linux-android21 x86_64-linux-android21; do
    for side in 0 1; do
        "$clang" --target="$target" -std=c11 -Wall -Wextra -Werror -fno-builtin \
            -DTERMUX_GLIBC_SIDE="$side" -fsyntax-only "$repo_dir/app/src/test/native/glibc-kernel-abi-test.c"
    done
    printf '%s\n' "$target kernel ABI checks passed"
done
