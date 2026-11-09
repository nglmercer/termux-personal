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
prefix=/data/data/com.termux/files/usr
{
    echo "commit: $(git rev-parse HEAD)$(git diff --quiet || echo ' (dirty)')"
    echo "android: $(adb_cmd shell getprop ro.build.version.release | tr -d '\r') / API $(adb_cmd shell getprop ro.build.version.sdk | tr -d '\r')"
    echo "device: $(adb_cmd shell getprop ro.product.manufacturer | tr -d '\r') $(adb_cmd shell getprop ro.product.model | tr -d '\r') ($(adb_cmd shell getprop ro.product.device | tr -d '\r'))"
    echo "rom: $(adb_cmd shell getprop ro.build.display.id | tr -d '\r')"
    echo "abi: $(adb_cmd shell getprop ro.product.cpu.abi | tr -d '\r')"
    echo "kernel: $(adb_cmd shell uname -r | tr -d '\r')"
    # Versions are read here, by the validation step, never by job.preview.
    echo "rustc: $(adb_cmd shell run-as com.termux $prefix/bin/rustc --version 2>/dev/null | tr -d '\r' || true)"
    echo "node: $(adb_cmd shell run-as com.termux $prefix/bin/node --version 2>/dev/null | tr -d '\r' || true)"
} | tee "$out/device.txt"
classes=com.termux.app.control.jobs.ResourcePressureInstrumentedTest
classes=$classes,com.termux.app.control.jobs.RpcOnDeviceInstrumentedTest
classes=$classes,com.termux.app.control.jobs.ManagedLimitsInstrumentedTest
classes=$classes,com.termux.app.control.jobs.WorkspaceExtrasInstrumentedTest
status=0
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class="$classes" || status=$?
results=app/build/outputs/androidTest-results/connected
echo "Gradle exit: $status. Per-test results (passed, failed, skipped = NOT_TESTED) are under $results" | tee -a "$out/device.txt"
exit "$status"
