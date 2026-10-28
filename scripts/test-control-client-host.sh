#!/bin/sh
# Host-only mock-socket and ADB adapter compatibility checks.
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
out_dir="$project_dir/build/control-client-host"
mkdir -p "$out_dir"
${CC:-cc} -std=c11 -O2 -Wall -Wextra -Werror tools/termux-control/termux-control.c -o "$out_dir/termux-control"
python3 tools/termux-control/python/test-client.py "$out_dir/termux-control" "${TMPDIR:-/tmp}"
python3 tools/termux-control/python/test-adb-adapters.py
