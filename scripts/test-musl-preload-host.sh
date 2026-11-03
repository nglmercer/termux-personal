#!/usr/bin/env bash
# Optional host experiment using an independently installed musl toolchain and an Android x86-64 shim.
# Run scripts/test-glibc-exec-host.sh first. No Android device behavior is implied.
set -euo pipefail
repo_dir=$(cd "$(dirname "$0")/.." && pwd)
: "${MUSL_CC:?Set MUSL_CC to the musl compiler wrapper}"
: "${MUSL_LOADER:?Set MUSL_LOADER to the absolute musl dynamic loader path}"
: "${ANDROID_EXEC_SHIM:?Set ANDROID_EXEC_SHIM to the built Android x86-64 exec shim}"
case "$MUSL_LOADER:$ANDROID_EXEC_SHIM" in /*:/*) ;; *) echo 'Loader and shim paths must be absolute.' >&2; exit 2 ;; esac
[[ -x "$MUSL_CC" && -f "$MUSL_LOADER" && -r "$ANDROID_EXEC_SHIM" ]] || { echo 'Missing compiler, loader, or shim.' >&2; exit 2; }
host="$repo_dir/build/glibc-exec-test"
out="$repo_dir/build/musl-preload-test"
mkdir -p "$out"
host_loader=$(readelf -l /bin/true | sed -n 's/.*interpreter: \(.*\)]/\1/p')
host_loader="$host/prefix/glibc/lib/$(basename "$host_loader")"
[[ -x "$host_loader" && -f "$host/side1.so" && -x "$host/probe" ]] || { echo 'Run scripts/test-glibc-exec-host.sh first.' >&2; exit 2; }
env -u LD_PRELOAD "$MUSL_CC" "-Wl,--dynamic-linker=$MUSL_LOADER" \
    "$repo_dir/app/src/test/native/glibc-exec-test.c" -o "$out/probe"
probe="$out/probe"

# The real musl loader can load this particular Android shim and run a musl child on this host.
line=$(env -u TERMUX_GLIBC_EXE -u TERMUX_BIONIC_LD_PRELOAD LD_PRELOAD="$ANDROID_EXEC_SHIM" "$probe" run "$probe")
[[ $line == "exe=$probe argv0=$probe glibc_exe=- preload=$ANDROID_EXEC_SHIM" ]] || { echo 'FAIL: musl child'; exit 1; }

# The existing glibc-side handoff restores the saved Bionic preload to the musl child.
line=$(env -u LD_PRELOAD TERMUX_GLIBC_EXE="$host/probe" TERMUX_BIONIC_LD_PRELOAD="$ANDROID_EXEC_SHIM" \
    "$host_loader" --library-path "$host/prefix/glibc/lib" --preload "$host/side1.so" \
    "$host/probe" run "$probe")
[[ $line == "exe=$probe argv0=$probe glibc_exe=- preload=$ANDROID_EXEC_SHIM" ]] || { echo 'FAIL: glibc-to-musl handoff'; exit 1; }
if [[ -n "${TERMUX_EXEC_SHIM:-}" ]]; then
    [[ "$TERMUX_EXEC_SHIM" == /* && -r "$TERMUX_EXEC_SHIM" ]] || { echo 'TERMUX_EXEC_SHIM must be an absolute readable path.' >&2; exit 2; }
    chain="$ANDROID_EXEC_SHIM:$TERMUX_EXEC_SHIM"
    # This particular Bionic preload is expected to fail with this host's musl, not on all systems.
    if failure=$(env -u TERMUX_GLIBC_EXE LD_PRELOAD="$chain" "$probe" report 2>&1); then
        echo 'FAIL: bundled Bionic preload unexpectedly loaded on this musl host' >&2; exit 1
    fi
    [[ $failure == *'Error relocating'* ]] || { echo 'FAIL: expected a dynamic loader relocation failure' >&2; exit 1; }
    if failure=$(env -u LD_PRELOAD TERMUX_GLIBC_EXE="$host/probe" TERMUX_BIONIC_LD_PRELOAD="$chain" \
        "$host_loader" --library-path "$host/prefix/glibc/lib" --preload "$host/side1.so" \
        "$host/probe" run "$probe" 2>&1); then
        echo 'FAIL: glibc-to-musl handoff unexpectedly accepted the Bionic preload' >&2; exit 1
    fi
    [[ $failure == *'Error relocating'* ]] || { echo 'FAIL: expected a handoff relocation failure' >&2; exit 1; }
    line=$(env -u LD_PRELOAD -u TERMUX_GLIBC_EXE "$probe" report)
    [[ $line == "exe=$probe argv0=$probe glibc_exe=- preload=-" ]] || { echo 'FAIL: explicit preload removal'; exit 1; }
    echo 'bundled Bionic preload relocation failure and explicit per-process removal reproduced'
fi
echo 'musl preload host checks passed; Android behavior remains untested'
