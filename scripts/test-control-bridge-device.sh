#!/bin/sh
# ARM64 Android native bridge test via the debug-only Termux ADB adapter.
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to an Android NDK}"
out_dir=build/control-bridge-device
mkdir -p "$out_dir"
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang" \
    -std=c11 -O2 -Wall -Wextra -Werror tools/termux-control/termux-control.c -o "$out_dir/termux-control"
python3 - "$out_dir/termux-control" tools/termux-control/python/test-bridge.py <<'PY'
import base64
import pathlib
import subprocess
import sys

binary, test = (base64.b64encode(pathlib.Path(path).read_bytes()).decode("ascii") for path in sys.argv[1:])
script = """set -eu
d=$(mktemp -d "$TMPDIR/control-bridge.XXXXXXXX")
trap 'rm -rf "$d"' EXIT
printf %s '@BIN@' | base64 -d > "$d/termux-control"
printf %s '@TEST@' | base64 -d > "$d/test.py"
chmod 700 "$d/termux-control"
python3 "$d/test.py" "$d/termux-control"
""".replace("@BIN@", binary).replace("@TEST@", test)
sys.exit(subprocess.run(["python3", "tools/termux-control/python/termux-adb", "exec"], input=script.encode()).returncode)
PY
