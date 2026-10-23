#!/bin/bash
# Запуск скомпилированного main-класса.
# usage: run.sh out_dir MainKt [args...]
set -u
SITE=${SITE:-/tmp/ktenv/lib/python3.11/site-packages}
J=${J:-$SITE/run_kotlin_kernel/jars}
JAVA=${JAVA:-$SITE/jdk4py/java-runtime/bin/java}
OUT=$1; shift
STD=$(ls "$J"/kotlin-stdlib-*.jar 2>/dev/null | grep -v sources | head -1)
exec "$JAVA" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
  -cp "$OUT:$STD" "$@"
