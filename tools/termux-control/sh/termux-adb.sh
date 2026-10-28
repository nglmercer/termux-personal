#!/usr/bin/env bash
# termux-adb.sh -- run shell commands as the Termux app user over ADB.
# Bash port of termux-adb (Python). Only needs bash + adb in PATH.
# Covers the session-independent modes; session IPC modes (run, focus,
# open, snapshot, ipc) need termux-adb (Python) or termux-adb.mjs (Node).
#
#   termux-adb.sh [-s SERIAL] shell              # interactive Termux shell
#   termux-adb.sh [-s SERIAL] exec 'pwd; id'     # run script, wait for status
#   termux-adb.sh [-s SERIAL] exec < script.sh   # read script from stdin
set -u
SERIAL=""
if [ "${1:-}" = "-s" ]; then SERIAL="${2:?missing serial}"; shift 2; fi
MODE="${1:?missing mode; usage: termux-adb.sh [-s SERIAL] shell|exec [script]}"
shift || true
command -v adb >/dev/null || { echo "adb was not found in PATH" >&2; exit 1; }

ROOT=/data/data/com.termux/files
REMOTE=(run-as com.termux env
  "HOME=$ROOT/home" "PREFIX=$ROOT/usr"
  "PATH=$ROOT/usr/bin:/system/bin" "TMPDIR=$ROOT/usr/tmp" "LANG=C.UTF-8"
  "$ROOT/usr/bin/bash")
# Minimal shlex.quote: safe chars pass through, anything else is
# single-quoted with embedded quotes escaped.
q() {
  local s="$1"
  if [[ "$s" =~ ^[A-Za-z0-9_@%+=:,./-]+$ ]]; then printf '%s' "$s"; else printf "'%s'" "${s//\'/\'\\\'\'}"; fi
}
quoted() { local p; for p in "$@"; do q "$p"; printf ' '; done; }

ADB=(adb)
[ -n "$SERIAL" ] && ADB+=(-s "$SERIAL")
case "$MODE" in
  shell)
    # shellcheck disable=SC2046
    exec "${ADB[@]}" shell -t "$(quoted "${REMOTE[@]}" -c 'cd "$HOME" && exec "$PREFIX/bin/bash" -l')"
    ;;
  exec)
    # shellcheck disable=SC2046
    if [ "$#" -gt 0 ]; then
      printf '%s\n' "$1" | "${ADB[@]}" shell -T "$(quoted "${REMOTE[@]}" -c 'cd "$HOME" && exec "$PREFIX/bin/bash" -s')"
    else
      exec "${ADB[@]}" shell -T "$(quoted "${REMOTE[@]}" -c 'cd "$HOME" && exec "$PREFIX/bin/bash" -s')"
    fi
    ;;
  *)
    echo "usage: termux-adb.sh [-s SERIAL] {shell|exec} [script]" >&2
    echo "session modes (run, focus, open, snapshot, ipc) need termux-adb or termux-adb.mjs" >&2
    exit 1
    ;;
esac
