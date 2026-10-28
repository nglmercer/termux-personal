#!/usr/bin/env bash
set -euo pipefail
repo_dir=$(cd "$(dirname "$0")/.." && pwd)
mkdir -p "$repo_dir/build/managed-path-test"
cc -std=c11 -Wall -Wextra -Werror -I "$repo_dir/app/src/main/cpp" \
    "$repo_dir/app/src/main/cpp/managed-path.c" "$repo_dir/app/src/test/native/managed-path-test.c" \
    -o "$repo_dir/build/managed-path-test/managed-path-test"
"$repo_dir/build/managed-path-test/managed-path-test"
