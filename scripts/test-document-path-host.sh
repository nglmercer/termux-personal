#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
: "${JAVA_HOME:?Set JAVA_HOME to a JDK}"
mkdir -p build/document-path-test
cc -std=c11 -Wall -Wextra -Werror -pthread -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
    app/src/test/host/document-path-test.c -o build/document-path-test/test
build/document-path-test/test
