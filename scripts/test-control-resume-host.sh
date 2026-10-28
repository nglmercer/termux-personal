#!/bin/sh
# Host-only replay protocol test; credentials and Android scheduler are simulated.
set -eu
: "${GSON_JAR:?Set GSON_JAR to gson-2.11.0.jar}"
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
app_classes="$project_dir/app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes"
terminal_classes="$project_dir/terminal-emulator/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes"
out_dir="$project_dir/build/control-resume-host"
mkdir -p "$out_dir/classes"
javac -cp "$app_classes:$terminal_classes:$GSON_JAR" -d "$out_dir/classes" app/src/test/host/com/termux/app/control/ipc/HostResumeSocketSmoke.java
python tools/termux-control/python/test-resume-host.py "$out_dir/classes:$app_classes:$terminal_classes:$GSON_JAR" "${TMPDIR:-/tmp}"
