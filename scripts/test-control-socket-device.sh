#!/bin/sh
# Requires compiled debug classes, Gson 2.11.0, Android SDK build-tools and authorized Termux ADB access.
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
: "${GSON_JAR:?Set GSON_JAR to gson-2.11.0.jar}"
out=build/control-socket-device
mkdir -p "$out/classes" "$out/dex"
android_jar="$ANDROID_HOME/platforms/android-35/android.jar"
app_classes=app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes
terminal_classes=terminal-emulator/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes
javac -source 8 -target 8 -cp "$android_jar:$app_classes:$terminal_classes:$GSON_JAR" -d "$out/classes" \
    app/src/test/device/com/termux/app/control/ipc/LocalSocketSmoke.java
"$ANDROID_HOME/build-tools/35.0.0/d8" --min-api 21 --lib "$android_jar" --output "$out/dex" \
    "$out/classes/com/termux/app/control/ipc/LocalSocketSmoke"*.class \
    "$app_classes/com/termux/app/control/ControlCore"*.class \
    "$app_classes/com/termux/app/control/OutputSubscriptions"*.class \
    "$app_classes/com/termux/app/control/ipc/"*.class \
    "$terminal_classes/com/termux/terminal/OutputReplayBuffer"*.class "$GSON_JAR"
python3 - "$out/dex/classes.dex" <<'PY'
import base64
import pathlib
import subprocess
import sys

dex = base64.b64encode(pathlib.Path(sys.argv[1]).read_bytes()).decode("ascii")
script = """set -eu
d=$(mktemp -d "$TMPDIR/control-socket.XXXXXXXX")
trap 'rm -rf "$d"' EXIT
printf %s 'DEX' | base64 -d > "$d/classes.dex"
chmod 400 "$d/classes.dex"
CLASSPATH="$d/classes.dex" app_process /system/bin com.termux.app.control.ipc.LocalSocketSmoke
""".replace("DEX", dex)
sys.exit(subprocess.run(["python3", "tools/termux-control/python/termux-adb", "exec"], input=script.encode()).returncode)
PY
