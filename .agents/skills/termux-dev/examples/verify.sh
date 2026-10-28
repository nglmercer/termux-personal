#!/usr/bin/env bash
# Verify a Termux on-device development setup, optionally for one project.
# Device-side script. Run from the termux-personal checkout on the PC:
#   python3 tools/termux-control/termux-adb exec "$(cat .agents/skills/termux-dev/examples/verify.sh)"
# Optional knobs (prepend as VAR=value assignments inside the exec string):
#   REPO_DIR=<dir under $HOME>   verify the checkout exists and is clean-ish
#   VERIFY_CMD='<shell command>'  run from the repo root; must exit 0
#   EXPECT_ANDROID_HOST=1         require a native aarch64-linux-android rustc
# Exits 0 only when every applicable check passes.
set -u
pass=0
fail=0
ok() { pass=$((pass + 1)); echo "PASS: $1"; }
bad() { fail=$((fail + 1)); echo "FAIL: $1"; }

[ -n "${HOME:-}" ] && [ -n "${PREFIX:-}" ] && ok "env HOME=$HOME PREFIX=$PREFIX" || bad "HOME/PREFIX env"
for t in git gh cargo rustc bun clang make cmake pkg-config python3 protoc; do
  if command -v "$t" >/dev/null 2>&1; then ok "$t $($t --version 2>&1 | head -1)"; else bad "$t missing"; fi
done
if [ "${EXPECT_ANDROID_HOST:-0}" = 1 ]; then
  if rustc -vV 2>/dev/null | grep -q 'host: .*android'; then
    ok "native android rustc ($(rustc -vV | sed -n 's/^host: //p'))"
  else
    bad "rustc host is not native android"
  fi
fi
if [ -n "${REPO_DIR:-}" ]; then
  if [ -d "$HOME/$REPO_DIR" ]; then
    ok "checkout $REPO_DIR present ($(git -C "$HOME/$REPO_DIR" log --oneline -1 2>/dev/null || echo unknown))"
  else
    bad "checkout $REPO_DIR missing in \$HOME"
  fi
  if [ -n "${VERIFY_CMD:-}" ]; then
    out="${TMPDIR:-$PREFIX/tmp}/verify-out.txt"
    if (cd "$HOME/$REPO_DIR" && eval "$VERIFY_CMD") >"$out" 2>&1; then
      ok "verify command exited 0"
    else
      bad "verify command failed:"; sed 's/^/    /' "$out"
    fi
  fi
fi
echo "---"
echo "pass=$pass fail=$fail"
[ "$fail" -eq 0 ]
