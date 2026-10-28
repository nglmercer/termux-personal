#!/bin/sh
# Host-only protocol endurance check; does not exercise Android LocalSocket.
set -eu
: "${GSON_JAR:?Set GSON_JAR to gson-2.11.0.jar}"
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
app_classes="$project_dir/app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes"
out_dir="$project_dir/build/control-lifetime-host"
mkdir -p "$out_dir/classes"
javac -cp "$app_classes:$GSON_JAR" -d "$out_dir/classes" app/src/test/host/com/termux/app/control/ipc/HostLifetimeSocketSmoke.java
python tools/termux-control/python/test-lifetime-host.py "$out_dir/classes:$app_classes:$GSON_JAR" "${TMPDIR:-/tmp}"
