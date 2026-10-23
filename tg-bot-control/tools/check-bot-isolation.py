#!/usr/bin/env python3
"""Проверка изоляции ботов (запускается в CI перед сборкой APK).

В LocalBotStore / BotRepository у большинства методов `botId: Long = -1L`,
а -1 означает «бот, активный в интерфейсе». Для экранов это нормально
(экран открыт для активного бота), но в сервисном слое (service/) и в
data/ такой вызов читает настройки ЧУЖОГО бота — так в v1.6.2 уточняющие
вопросы @AppBotcontrol_bot отвечали во всех ботах.

Правило: в service/ и data/ каждый такой вызов передаёт botId явно.
usage: python3 tools/check-bot-isolation.py   (из tg-bot-control/)
"""
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                    "android", "app", "src", "main", "java", "com", "botcontrol", "admin")
STRICT_DIRS = ("service", "data")
# Где -1 = «все/активный» используется осознанно.
ALLOW = {
    ("data", "LocalBotStore.kt"),  # само хранилище
}


def methods_with_default(path):
    src = open(path, encoding="utf-8").read()
    return {m.group(1) for m in re.finditer(r"suspend fun (\w+)\(([^)]*)botId: Long = -1L", src)}


def arg_count(args):
    depth, n, cur = 0, 0, ""
    for ch in args:
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if ch == "," and depth == 0:
            n += 1 if cur.strip() else 0
            cur = ""
        else:
            cur += ch
    return n + (1 if cur.strip() else 0)


def main():
    store_m = methods_with_default(os.path.join(ROOT, "data", "LocalBotStore.kt"))
    repo_m = methods_with_default(os.path.join(ROOT, "data", "BotRepository.kt"))
    bad = []
    for sub in STRICT_DIRS:
        for dp, _, files in os.walk(os.path.join(ROOT, sub)):
            for f in files:
                if not f.endswith(".kt") or (sub, f) in ALLOW:
                    continue
                path = os.path.join(dp, f)
                text = open(path, encoding="utf-8").read()
                for m in re.finditer(r"\b(\w*(?:[sS]tore|[rR]epository))\.(\w+)\(", text):
                    obj, name = m.group(1), m.group(2)
                    known = store_m if "tore" in obj else repo_m
                    if name not in known:
                        continue
                    i, depth, j = m.end(), 1, m.end()
                    while depth and j < len(text):
                        depth += {"(": 1, ")": -1}.get(text[j], 0)
                        j += 1
                    args = text[i:j - 1]
                    need = 2 if name.startswith("set") else 1
                    if name == "saveBotToken":
                        need = 3
                    if "botId" in args or arg_count(args) >= need:
                        continue
                    line = text.count("\n", 0, m.start()) + 1
                    rel = os.path.relpath(path, ROOT)
                    bad.append(f"{rel}:{line}: {obj}.{name}({args.strip()[:40]}) — нет botId (читает активного бота)")
    if bad:
        print("✗ Нарушена изоляция ботов:")
        print("\n".join("  " + b for b in bad))
        sys.exit(1)
    print(f"✓ Изоляция ботов: service/ и data/ передают botId явно "
          f"({len(store_m)} методов хранилища, {len(repo_m)} репозитория)")


if __name__ == "__main__":
    main()
