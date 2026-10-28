#!/usr/bin/env bash
set -euo pipefail
repo_dir=$(cd "$(dirname "$0")/.." && pwd)
ndk_dir=${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to an installed Android NDK}
toolchain="$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64/bin"
mkdir -p "$repo_dir/build/managed-fork-test"
for triple in aarch64-linux-android21 armv7a-linux-androideabi21 i686-linux-android21 x86_64-linux-android21; do
    "$toolchain/$triple-clang" -std=c11 -Wall -Wextra -Werror -fPIC -c \
        "$repo_dir/app/src/main/cpp/managed-fork.c" -o "$repo_dir/build/managed-fork-test/$triple.o"
    "$toolchain/$triple-clang" -std=c11 -Wall -Wextra -Werror -fPIC -c \
        "$repo_dir/app/src/main/cpp/managed-path.c" -o "$repo_dir/build/managed-fork-test/$triple-path.o"
    printf '%s compiled\n' "$triple"
done
