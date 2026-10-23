#!/bin/bash
# Компиляция исходников импортёра без Android.
# usage: kc.sh out_dir files...
# Переменные окружения (необязательно):
#   J    — каталог с kotlin-jupyter-kernel-*-all.jar и kotlin-stdlib-*.jar
#   JAVA — путь к java (JRE 17+)
set -u
SITE=${SITE:-/tmp/ktenv/lib/python3.11/site-packages}
J=${J:-$SITE/run_kotlin_kernel/jars}
JAVA=${JAVA:-$SITE/jdk4py/java-runtime/bin/java}
OUT=$1; shift
mkdir -p "$OUT"
ALL=$(ls "$J"/kotlin-jupyter-kernel-*-all.jar 2>/dev/null | head -1)
STD=$(ls "$J"/kotlin-stdlib-*.jar 2>/dev/null | grep -v sources | head -1)
if [ -z "${ALL:-}" ] || [ -z "${STD:-}" ]; then
  echo "Не найдены jar-ы в $J. Установи: pip install jdk4py==21.0.8.2 kotlin-jupyter-kernel" >&2
  exit 2
fi
"$JAVA" -Xmx1500m -cp "$ALL" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -cp "$STD:$OUT" -jvm-target 17 -nowarn "$@" -d "$OUT" 2>&1 \
  | grep -v "^warning"
exit ${PIPESTATUS[0]}
