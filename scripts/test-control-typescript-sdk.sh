#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
: "${GSON_JAR:?Set GSON_JAR to gson-2.11.0.jar}"
app_classes="$project_dir/app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes"
out_dir="$project_dir/build/control-typescript-sdk"
mkdir -p "$out_dir/classes"
npm --prefix sdk/typescript run check
npm --prefix sdk/typescript test
javac -cp "$app_classes:$GSON_JAR" -d "$out_dir/classes" app/src/test/host/com/termux/app/control/ipc/HostRpcSocketSmoke.java
python3 sdk/typescript/test/java-interop.py "$out_dir/classes:$app_classes:$GSON_JAR"
${CC:-cc} -std=c11 -O2 -Wall -Wextra -Werror tools/termux-control/termux-control.c -o "$out_dir/termux-control"
python3 sdk/typescript/test/adb-java-interop.py "$out_dir/classes:$app_classes:$GSON_JAR" "$out_dir/termux-control"
