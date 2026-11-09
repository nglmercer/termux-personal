#!/bin/sh
# Compile and exercise the JNI PTY on a host where clang builds runnable native code.
set -eu
: "${JAVA_HOME:?Set JAVA_HOME to a JDK with JNI headers}"
: "${TERMUX_PTY_SHELL:?Set TERMUX_PTY_SHELL to an executable local shell}"
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
out_dir="$project_dir/build/headless-pty"
mkdir -p "$out_dir/classes"
clang -std=c11 -D_GNU_SOURCE -shared -fPIC -Wall -Wextra -Werror \
    -I "$JAVA_HOME/include" -I "$JAVA_HOME/include/linux" \
    terminal-emulator/src/main/jni/termux.c -o "$out_dir/libtermux.so"
javac -d "$out_dir/classes" terminal-emulator/src/main/java/com/termux/terminal/JNI.java \
    terminal-emulator/src/main/java/com/termux/terminal/SessionExitStatus.java \
    terminal-emulator/src/test/host/HeadlessPtySmoke.java
LD_LIBRARY_PATH="$out_dir${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
    java --add-opens java.base/java.io=ALL-UNNAMED -cp "$out_dir/classes" com.termux.terminal.HeadlessPtySmoke
# A separate test-only library injects pre-fork failures; production code is not modified.
mkdir -p "$out_dir/faults"
clang -std=c11 -D_GNU_SOURCE -shared -fPIC -Wall -Wextra -Werror \
    -I "$JAVA_HOME/include" -I "$JAVA_HOME/include/linux" \
    terminal-emulator/src/main/jni/termux.c terminal-emulator/src/test/host/pty-faults.c \
    -Wl,--wrap=grantpt -Wl,--wrap=fork -o "$out_dir/faults/libtermux.so"
for failure in grantpt fork; do
    TERMUX_PTY_FAIL_TEST="$failure" LD_LIBRARY_PATH="$out_dir/faults${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
        java --add-opens java.base/java.io=ALL-UNNAMED -cp "$out_dir/classes" com.termux.terminal.HeadlessPtySmoke
done
