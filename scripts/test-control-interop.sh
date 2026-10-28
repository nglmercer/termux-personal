#!/bin/sh
# Host-only protocol interoperability test; Android socket permissions are not tested.
set -eu
: "${GSON_JAR:?Set GSON_JAR to gson-2.11.0.jar}"
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
app_classes="$project_dir/app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes"
if [ ! -f "$app_classes/com/termux/app/control/ipc/RpcConnection.class" ]; then
    echo "Compile app debug Java classes with Gradle first." >&2
    exit 1
fi
out_dir="$project_dir/build/control-interop"
mkdir -p "$out_dir/classes" "$out_dir/tmp"
clang -std=c11 -O2 -Wall -Wextra -Werror tools/termux-control/termux-control.c -o "$out_dir/termux-control"
javac -cp "$app_classes:$GSON_JAR" -d "$out_dir/classes" app/src/test/host/com/termux/app/control/ipc/HostRpcSocketSmoke.java
python tools/termux-control/python/test-java-interop.py "$out_dir/termux-control" "$out_dir/classes:$app_classes:$GSON_JAR" "$out_dir/tmp"
