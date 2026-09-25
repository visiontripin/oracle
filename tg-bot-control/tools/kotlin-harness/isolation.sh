#!/bin/bash
# Тест изоляции ботов: наборы в экспорте/импорте + метка бота в журнале.
# usage: ./isolation.sh   → «ISOLATION OK»
set -eu
cd "$(dirname "$0")"
SRC=../../android/app/src/main/java/com/botcontrol/admin
OUT=${OUT:-/tmp/kiso}
# BotLog.kt Android-зависим (DeviceLlm) — берём копию чистой функции tagged().
python3 - "$SRC/service/BotLog.kt" > /tmp/Tagged.kt <<'PY'
import re, sys
s = open(sys.argv[1], encoding="utf-8").read()
m = re.search(r"    fun tagged\(tag: String, line: String\): String \{.*?\n    \}\n", s, re.S)
print("fun tagged" + m.group(0).split("fun tagged", 1)[1])
PY
./kc.sh "$OUT" $SRC/data/PySource.kt $SRC/data/ScriptImporter.kt $SRC/data/BotExtras.kt \
  $SRC/data/Anim.kt $SRC/data/SettingsExporter.kt stubs/*.kt stubs2/*.kt src/Isolation.kt /tmp/Tagged.kt
./run.sh "$OUT" IsolationKt
