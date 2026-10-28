#!/bin/sh
# Requires a compiled debug build, Android SDK build-tools, Python, and authorized Termux ADB access.
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
out=build/document-jni-device
mkdir -p "$out/classes" "$out/dex"
android_jar="$ANDROID_HOME/platforms/android-35/android.jar"
javac -source 8 -target 8 -cp "$android_jar" -d "$out/classes" \
    app/src/main/java/com/termux/filepicker/SafeDocumentOpen.java \
    app/src/test/device/com/termux/filepicker/DocumentJniSmoke.java
"$ANDROID_HOME/build-tools/35.0.0/d8" --min-api 21 --lib "$android_jar" --output "$out/dex" \
    "$out/classes/com/termux/filepicker/SafeDocumentOpen.class" \
    "$out/classes/com/termux/filepicker/SafeDocumentOpen\$Entry.class" \
    "$out/classes/com/termux/filepicker/DocumentJniSmoke.class"
python3 - "$out/dex/classes.dex" app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/arm64-v8a/libtermux-document-path.so <<'PY'
import base64
import pathlib
import subprocess
import sys

dex, library = (base64.b64encode(pathlib.Path(path).read_bytes()).decode("ascii") for path in sys.argv[1:])
script = """set -eu
d=$(mktemp -d "$TMPDIR/document-jni.XXXXXXXX")
trap 'rm -rf "$d"' EXIT
printf %s 'DEX' | base64 -d > "$d/classes.dex"
printf %s 'LIB' | base64 -d > "$d/libtermux-document-path.so"
chmod 400 "$d/classes.dex"
CLASSPATH="$d/classes.dex" app_process -Djava.library.path="$d" /system/bin com.termux.filepicker.DocumentJniSmoke
""".replace("DEX", dex).replace("LIB", library)
sys.exit(subprocess.call(["python3", "tools/termux-control/python/termux-adb", "exec", script]))
PY
