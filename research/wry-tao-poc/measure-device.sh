#!/bin/sh
# Installs the PoC APK (a separate package, never the terminal app) and records startup latency, memory and the
# lifecycle events Tao reports, from cold start through pause, resume and destruction. Read-only for the phone
# otherwise; uninstall with: adb uninstall com.termux.wrytaopoc
set -eu
apk=${1:?usage: measure-device.sh APK}
pkg=com.termux.wrytaopoc
act=$pkg/.MainActivity
a() { adb ${ANDROID_SERIAL:+-s "$ANDROID_SERIAL"} "$@"; }
a install -r "$apk" >/dev/null
echo "android: $(a shell getprop ro.build.version.release | tr -d '\r') / API $(a shell getprop ro.build.version.sdk | tr -d '\r'), abi $(a shell getprop ro.product.cpu.abi | tr -d '\r')"
echo "webview: $(a shell dumpsys webviewupdate | grep -m1 'Current WebView package' | tr -d '\r')"
for run in 1 2 3; do
    a shell am force-stop $pkg
    sleep 1
    a logcat -c
    echo "cold start $run: $(a shell am start -W -n $act | grep -E 'TotalTime|WaitTime' | tr -d '\r' | tr '\n' ' ')"
    sleep 4
    a logcat -d -s wrytaopoc:I | grep -o 'lifecycle ready after [0-9]* ms' || true
    a logcat -d | grep -o 'wrytaopoc page-shown [0-9]*' | head -1 || true
done
echo "memory after start: $(a shell dumpsys meminfo $pkg | grep -E 'TOTAL PSS|TOTAL RSS|TOTAL:' | head -2 | tr -s ' ' | tr -d '\r' | tr '\n' ' ')"
a logcat -c
a shell input keyevent 3; sleep 3                      # pause: home screen
a shell am start -n $act >/dev/null; sleep 3           # resume
a shell input keyevent 4; sleep 3                      # back: finishes the Activity
a shell am force-stop $pkg; sleep 1
echo "lifecycle (rust/tao):"; a logcat -d -s wrytaopoc:I | grep -o 'wrytaopoc .*' || true
echo "lifecycle (page):"; a logcat -d | grep -o 'wrytaopoc visibility [a-z]*' || true
