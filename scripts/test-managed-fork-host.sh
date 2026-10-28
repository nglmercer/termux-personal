#!/usr/bin/env bash
set -euo pipefail
repo_dir=$(cd "$(dirname "$0")/.." && pwd)
mkdir -p "$repo_dir/build/managed-fork-test"
cc -std=c11 -Wall -Wextra -Werror -I "$repo_dir/app/src/main/cpp" \
    "$repo_dir/app/src/main/cpp/managed-fork.c" "$repo_dir/app/src/test/native/managed-fork-test.c" \
    -o "$repo_dir/build/managed-fork-test/managed-fork-test"
"$repo_dir/build/managed-fork-test/managed-fork-test"
