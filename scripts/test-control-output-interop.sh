#!/bin/sh
# Host-only Unix socket output test; does not exercise Android LocalSocket or a real PTY.
set -eu
: "${GSON_JAR:?Set GSON_JAR to gson-2.11.0.jar}"
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
app_classes="$project_dir/app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes"
terminal_classes="$project_dir/terminal-emulator/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes"
out_dir="$project_dir/build/control-output-interop"
mkdir -p "$out_dir/classes"
socket_tmp=${TMPDIR:-/tmp}
javac -cp "$app_classes:$terminal_classes:$GSON_JAR" -d "$out_dir/classes" app/src/test/host/com/termux/app/control/ipc/HostOutputSocketSmoke.java
python tools/termux-control/python/test-output-interop.py "$out_dir/classes:$app_classes:$terminal_classes:$GSON_JAR" "$socket_tmp"

clang -std=c11 -O2 -Wall -Wextra -Werror tools/termux-control/termux-control.c -o "$out_dir/termux-control"
python tools/termux-control/python/test-follow-host.py "$out_dir/classes:$app_classes:$terminal_classes:$GSON_JAR" "$socket_tmp" "$out_dir/termux-control"

# Exercise interactive key forwarding and resize through the same simulated Java protocol loop.
javac -cp "$app_classes:$terminal_classes:$GSON_JAR" -d "$out_dir/classes" app/src/test/host/com/termux/app/control/ipc/HostAttachSocketSmoke.java
python tools/termux-control/python/test-attach-host.py "$out_dir/classes:$app_classes:$terminal_classes:$GSON_JAR" "$socket_tmp" "$out_dir/termux-control"
