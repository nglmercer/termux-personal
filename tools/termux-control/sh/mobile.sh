#!/bin/sh
# Adaptive mobile automation helper (shell parity with python/mobile and js/mobile).
# Requires only adb, base64 and sh. Probes before acting.
set -eu

SERIAL="${ADB_SERIAL:-}"
if [ -z "$SERIAL" ]; then
  SERIAL=$(adb devices | awk '$2 == "device" { print $1; exit }')
fi
[ -n "$SERIAL" ] || { echo "no authorized adb device found" >&2; exit 3; }

ADB="adb -s $SERIAL"
MODE="${1:-}"
shift || true

case "$MODE" in
  device) echo "$SERIAL" ;;
  info)
    model=$($ADB shell getprop ro.product.model | tr -d '\r')
    android=$($ADB shell getprop ro.build.version.release | tr -d '\r')
    if $ADB shell 'run-as com.termux id' >/dev/null 2>&1; then path=run-as
    elif $ADB shell 'pm path com.termux.test' >/dev/null 2>&1 && $ADB shell 'pm path com.termux.test' | grep -q 'package:'; then path=instrumented
    else path=blocked-by-rom; fi
    printf '{\n  "serial": "%s",\n  "model": "%s",\n  "android": "%s",\n  "path": "%s"\n}\n' "$SERIAL" "$model" "$android" "$path"
    ;;
  exec)
    SCRIPT="$*"
    [ -n "$SCRIPT" ] || { echo "empty script" >&2; exit 2; }
    if $ADB shell 'run-as com.termux id' >/dev/null 2>&1; then
      ROOT=/data/data/com.termux/files
      printf '%s' "$SCRIPT" | $ADB shell -T "run-as com.termux env HOME=$ROOT/home PREFIX=$ROOT/usr PATH=$ROOT/usr/bin:/system/bin TMPDIR=$ROOT/usr/tmp LANG=C.UTF-8 $ROOT/usr/bin/bash -c 'cd \"\$HOME\" && exec \"\$PREFIX/bin/bash\" -s'"
      exit $?
    fi
    if $ADB shell 'pm path com.termux.test' | grep -q 'package:'; then
      ENCODED=$(printf '%s' "$SCRIPT" | base64 | tr -d '\n' | tr -d '=')
      RESULT=/sdcard/termux-agent/result.json
      $ADB shell "rm -f $RESULT"
      $ADB shell "am instrument -w -r -e class com.termux.app.control.AgentCommandInstrumentedTest -e cmd $ENCODED com.termux.test/androidx.test.runner.AndroidJUnitRunner" >/dev/null 2>&1 || true
      OUT=$($ADB shell "cat $RESULT" 2>/dev/null || true)
      [ -n "$OUT" ] || { echo "instrumented exec produced no result" >&2; exit 1; }
      echo "$OUT"
      echo "$OUT" | grep -q '"ok":true' && exit 0 || exit 1
    fi
    echo "automatic exec unavailable on this ROM (path=blocked-by-rom); see mobile guide" >&2
    exit 2
    ;;
  *)
    echo "usage: mobile.sh <info|device|exec> [script]" >&2
    exit 2
    ;;
esac
