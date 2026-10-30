#!/bin/sh
# Managed jobs, processes and workspaces through the production socket of an installed debug build.
# Requires authorized ADB, the Android NDK, and local control started in the app with read/write access
# and the job, process and workspace options checked. Installs the native client as files/termux-control-adb.
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to an Android NDK}"
out=build/control-jobs-device
mkdir -p "$out"
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang" \
    -std=c11 -O2 -Wall -Wextra -Werror tools/termux-control/termux-control.c -o "$out/termux-control"
adb ${ANDROID_SERIAL:+-s "$ANDROID_SERIAL"} push "$out/termux-control" /data/local/tmp/termux-control-adb >/dev/null
adb ${ANDROID_SERIAL:+-s "$ANDROID_SERIAL"} shell run-as com.termux cp /data/local/tmp/termux-control-adb files/termux-control-adb
adb ${ANDROID_SERIAL:+-s "$ANDROID_SERIAL"} shell run-as com.termux chmod 700 files/termux-control-adb
PYTHONPATH=sdk/python python3 sdk/python/tests/device_jobs_e2e.py ${ANDROID_SERIAL:+"$ANDROID_SERIAL"}
