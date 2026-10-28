#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
: "${GSON_JAR:?Set GSON_JAR to gson-2.11.0.jar}"
app_classes="$project_dir/app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes"
out_dir="$project_dir/build/control-python-sdk"
mkdir -p "$out_dir/classes"
PYTHONPATH=sdk/python python3 -m unittest discover -s sdk/python/tests -p 'test_*.py'
javac -cp "$app_classes:$GSON_JAR" -d "$out_dir/classes" app/src/test/host/com/termux/app/control/ipc/HostRpcSocketSmoke.java
PYTHONPATH=sdk/python python3 sdk/python/tests/java_interop.py "$out_dir/classes:$app_classes:$GSON_JAR"
