#!/bin/sh
# Test the TypeScript ADB transport against an isolated Android LocalSocket server.
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to an Android NDK}"
: "${GSON_JAR:?Set GSON_JAR to gson-2.11.0.jar}"
out=build/control-adb-device
mkdir -p "$out/classes" "$out/dex"
android_jar="$ANDROID_HOME/platforms/android-35/android.jar"
app_classes=app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes
terminal_classes=terminal-emulator/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes
javac -source 8 -target 8 -cp "$android_jar:$app_classes:$terminal_classes:$GSON_JAR" -d "$out/classes" \
    app/src/test/device/com/termux/app/control/ipc/AdbBridgeServer.java
"$ANDROID_HOME/build-tools/35.0.0/d8" --min-api 21 --lib "$android_jar" --output "$out/dex" \
    "$out/classes/com/termux/app/control/ipc/AdbBridgeServer"*.class \
    "$app_classes/com/termux/app/control/ControlCore"*.class \
    "$app_classes/com/termux/app/control/OutputSubscriptions"*.class \
    "$app_classes/com/termux/app/control/ipc/"*.class \
    "$terminal_classes/com/termux/terminal/OutputReplayBuffer"*.class "$GSON_JAR"
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang" \
    -std=c11 -O2 -Wall -Wextra -Werror tools/termux-control/termux-control.c -o "$out/termux-control"
python3 - "$out/dex/classes.dex" "$out/termux-control" <<'PY'
import base64
import pathlib
import select
import os
import subprocess
import sys

dex, binary = (base64.b64encode(pathlib.Path(path).read_bytes()).decode("ascii") for path in sys.argv[1:])
script = """set -eu
d=$(mktemp -d "$TMPDIR/adb-bridge.XXXXXXXX")
trap 'rm -rf "$d"' EXIT
printf %s 'DEX' | base64 -d > "$d/classes.dex"
printf %s 'BIN' | base64 -d > "$d/termux-control"
chmod 400 "$d/classes.dex"
chmod 700 "$d/termux-control"
CLASSPATH="$d/classes.dex" app_process /system/bin com.termux.app.control.ipc.AdbBridgeServer "$d/termux-control"
""".replace("DEX", dex).replace("BIN", binary)
server = subprocess.Popen(["python3", "tools/termux-control/python/termux-adb", "exec"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
try:
    try:
        server.stdin.write(script.encode())
    except BrokenPipeError as exc:
        raise RuntimeError(server.stderr.read().decode(errors="replace")) from exc
    server.stdin.close()
    ready, _, _ = select.select([server.stdout], [], [], 15)
    if not ready:
        raise RuntimeError("Android test server did not start")
    line = server.stdout.readline().decode().strip().split()
    if len(line) != 3 or line[0] != "READY":
        raise RuntimeError("Android test server failed to start")
    subprocess.run(["node", "sdk/typescript/test/adb-device-interop.ts", line[1], line[2]], check=True, timeout=15)
    env = dict(os.environ, PYTHONPATH="sdk/python")
    subprocess.run(["python3", "sdk/python/tests/adb_device_interop.py", line[1], line[2]], check=True, timeout=15, env=env)
    if server.wait(timeout=15) != 0:
        raise RuntimeError("Android test server failed")
finally:
    if server.poll() is None:
        server.kill()
        server.wait()
PY
