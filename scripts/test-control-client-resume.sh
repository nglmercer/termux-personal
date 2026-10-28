#!/bin/sh
# Host protocol and pseudo-terminal tests; not an Android or live Termux test.
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
out_dir="$project_dir/build/control-client-resume"
mkdir -p "$out_dir"
${CC:-cc} -std=c11 -O2 -Wall -Wextra -Werror tools/termux-control/termux-control.c -o "$out_dir/termux-control"
socket_tmp=${TMPDIR:-/tmp}
python tools/termux-control/python/test-client-resume.py "$out_dir/termux-control" "$socket_tmp"
python tools/termux-control/python/test-attach-resume.py "$out_dir/termux-control" "$socket_tmp"
python tools/termux-control/python/test-follow.py "$out_dir/termux-control" "$socket_tmp"
python tools/termux-control/python/test-attach.py "$out_dir/termux-control" "$socket_tmp"
