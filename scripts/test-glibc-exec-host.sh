#!/usr/bin/env bash
# Host test for the glibc execve shim: a copy of the host glibc loader stands in for Termux's.
set -euo pipefail
repo_dir=$(cd "$(dirname "$0")/.." && pwd)
out="$repo_dir/build/glibc-exec-test"
rm -rf "$out"; mkdir -p "$out/prefix/glibc/lib"
loader=$(readelf -l /bin/true | sed -n 's/.*interpreter: \(.*\)]/\1/p')
cp "$loader" "$out/prefix/glibc/lib/$(basename "$loader")"
# Side 0 stands in for the bionic build: it resolves the next execve with dlsym, as on Android.
cc -std=c11 -Wall -Wextra -Werror -Os -fPIC -shared -fno-builtin -fno-stack-protector \
    -DTERMUX_GLIBC_SIDE=0 -DTERMUX_PREFIX="\"$out/prefix\"" "$repo_dir/app/src/main/cpp/glibc-exec.c" -o "$out/side0.so" -ldl
cc -std=c11 -Wall -Wextra -Werror -Os -fPIC -shared -nostdlib -ffreestanding -fno-builtin -fno-stack-protector \
    -DTERMUX_GLIBC_SIDE=1 -DTERMUX_PREFIX="\"$out/prefix\"" "$repo_dir/app/src/main/cpp/glibc-exec.c" -o "$out/side1.so"
cc -std=c11 -Wall -Wextra -Werror "$repo_dir/app/src/test/native/glibc-exec-test.c" -o "$out/probe"
fake="$out/prefix/glibc/lib/$(basename "$loader")"

# A glibc program executed from a "bionic" process runs through the Termux loader, without LD_PRELOAD.
line=$(env LD_PRELOAD="$out/side0.so" TERMUX_GLIBC_PRELOAD="$out/side1.so" "$out/probe" run "$out/probe")
echo "$line"
[[ $line == "exe=$fake argv0=$out/probe glibc_exe=$out/probe preload=-" ]] || { echo "FAIL: loader redirect"; exit 1; }

# Re-executing /proc/self/exe (the loader) with ordinary arguments reaches the real program again.
# Without the shim the loader would take "report" as the program to load and fail.
relaunch=$(env TERMUX_GLIBC_EXE="$out/probe" TERMUX_GLIBC_PRELOAD="$out/side1.so" "$fake" --library-path "$out/prefix/glibc/lib" \
    --preload "$out/side1.so" "$out/probe" relaunch 2>&1)
echo "$relaunch"
[[ $relaunch == "exe=$fake argv0=$fake glibc_exe=$out/probe preload=-" ]] || { echo "FAIL: self relaunch"; exit 1; }

# A glibc process running a non-glibc child hands back the saved bionic LD_PRELOAD.
script="$out/child.sh"; printf '#!/bin/sh\necho "child preload=$LD_PRELOAD"\n' > "$script"; chmod 755 "$script"
# On this host the script's /bin/sh is itself glibc, so it reports the missing fake library; that is expected.
child=$(env TERMUX_GLIBC_EXE="$out/probe" TERMUX_BIONIC_LD_PRELOAD=/fake/termux-exec.so "$fake" --library-path "$out/prefix/glibc/lib" \
    --preload "$out/side1.so" "$out/probe" run "$script" 2>/dev/null || true)
echo "$child"
[[ $child == "child preload=/fake/termux-exec.so" ]] || { echo "FAIL: bionic preload restore"; exit 1; }

# Passing through: a missing program keeps its ordinary error.
missing=$(env LD_PRELOAD="$out/side0.so" "$out/probe" run /nonexistent 2>&1 || true)
[[ $missing == *"No such file"* ]] || { echo "FAIL: passthrough errno: $missing"; exit 1; }
echo "glibc exec shim host checks passed"
