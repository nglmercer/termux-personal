#!/usr/bin/env bash
# List any on-device CLI's surface: its top-level help plus one help screen
# per subcommand. No running services required.
# Device-side script. The target binary comes from $1 or $BIN.
# Run from the termux-personal checkout on the PC (stdin carries no
# arguments, so pass the path in the same script string):
#   python3 tools/termux-control/termux-adb exec "BIN=\$HOME/<repo>/<binary> $(cat .agents/skills/termux-dev/examples/list-commands.sh)"
# With no subcommand arguments, candidate subcommands are probed from the
# top-level help text automatically (only ones that answer with exit 0
# and non-empty output are shown).
set -u
BIN="${1:-${BIN:-}}"
[ -n "$BIN" ] || { echo "usage: list-commands.sh <binary> [subcommand...] (or set \$BIN)" >&2; exit 1; }
ORIG="$BIN"
case "$BIN" in */*) ;; *) BIN="$(command -v "$BIN" 2>/dev/null)" || BIN="";; esac
[ -n "$BIN" ] && [ -x "$BIN" ] || { echo "not executable: $ORIG" >&2; exit 1; }
shift
HELP="$("$BIN" --help 2>&1 || "$BIN" -h 2>&1 || "$BIN" help 2>&1 || true)"
[ -n "$HELP" ] || { echo "no help output from $BIN" >&2; exit 1; }
echo "$HELP"
if [ "$#" -eq 0 ]; then
  set -- $(echo "$HELP" | grep -oE '^[[:space:]]+[a-z][a-z0-9_.-]+' | awk '{print $1}' | sort -u)
fi
for sub in "$@"; do
  case "$sub" in usage:|commands:|options:|args:|[a-z]*:*|@*|/bin/*|-) continue;; esac
  out=""
  for form in "$sub --help" "$sub help" "help $sub"; do
    # shellcheck disable=SC2086
    if out="$($BIN $form 2>/dev/null)" && [ -n "$out" ] && [ "$out" != "$HELP" ]; then break; fi
    out=""
  done
  [ -n "$out" ] || continue
  echo
  echo "=== $BIN $sub ==="
  echo "$out"
done
