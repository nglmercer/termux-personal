#!/bin/sh
# Issue #11: resource profiles, admission, cancellation and injected pressure on one connected phone.
# Requires authorized ADB and a debuggable com.termux build. It runs the instrumented resource classes (real launcher,
# real /proc, device readings injected through the supervisor Sampler; nothing exhausts memory or heats the device)
# and writes the device metadata the report needs next to the Gradle results. Rust and Node cases are skipped,
# not failed, when the toolchains are not installed in the Termux prefix: record those as NOT_TESTED.
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
adb_cmd() { adb ${ANDROID_SERIAL:+-s "$ANDROID_SERIAL"} "$@"; }
out=build/control-resources-device
mkdir -p "$out"
{
    echo "commit: $(git rev-parse HEAD)$(git diff --quiet || echo ' (dirty)')"
    echo "android: $(adb_cmd shell getprop ro.build.version.release | tr -d '\r') / API $(adb_cmd shell getprop ro.build.version.sdk | tr -d '\r')"
    echo "device: $(adb_cmd shell getprop ro.product.manufacturer | tr -d '\r') $(adb_cmd shell getprop ro.product.model | tr -d '\r') ($(adb_cmd shell getprop ro.product.device | tr -d '\r'))"
    echo "rom: $(adb_cmd shell getprop ro.build.display.id | tr -d '\r')"
    echo "abi: $(adb_cmd shell getprop ro.product.cpu.abi | tr -d '\r')"
    echo "kernel: $(adb_cmd shell uname -r | tr -d '\r')"
} | tee "$out/device.txt"
# Install over the existing app (keeps the Termux home and prefix) instead of connectedDebugAndroidTest, which
# uninstalls the app when it finishes. The signing key must match the installed build.
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
abi=$(adb_cmd shell getprop ro.product.cpu.abi | tr -d '\r')
adb_cmd install -r "app/build/outputs/apk/debug/termux-app_apt-android-7-debug_$abi.apk"
adb_cmd install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
classes=com.termux.app.control.jobs.ResourcePressureInstrumentedTest
classes=$classes,com.termux.app.control.jobs.RpcOnDeviceInstrumentedTest
classes=$classes,com.termux.app.control.jobs.ManagedLimitsInstrumentedTest
classes=$classes,com.termux.app.control.jobs.WorkspaceExtrasInstrumentedTest
adb_cmd logcat -c
adb_cmd shell am instrument -w -r -e class "$classes" com.termux.test/androidx.test.runner.AndroidJUnitRunner | tee "$out/instrument.txt"
# Tool versions are read by the test itself (run as a job, never by job.preview), since run-as is blocked on some ROMs.
adb_cmd logcat -d -s ResourcePressureTest:I | tee -a "$out/device.txt"
# A skipped Rust/Node case (tool not installed) is an assumption failure: record it as NOT_TESTED.
grep -q '^OK (' "$out/instrument.txt"
