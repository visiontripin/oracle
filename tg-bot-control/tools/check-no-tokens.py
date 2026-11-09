#!/usr/bin/env python3
"""CI-страж: в рабочей копии не должно быть живых токенов Telegram-ботов.

Запускается в GitHub Actions перед сборкой APK и BotControl.exe.
Ищем паттерн токена BotFather (число : 35 символов) в текстовых файлах.
Любая находка = ошибка; исключения добавляются ТОЛЬКО осознанно.

usage: python3 tools/check-no-tokens.py   (из tg-bot-control/ или откуда угодно)
"""
import os
import re
import sys

PATTERN = re.compile(r"\b\d{8,10}:[A-Za-z0-9_-]{30,45}\b")
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
SKIP_DIRS = {".git", "build", ".gradle", "node_modules", "dist", "__pycache__",
             ".venv", "out", "out2", ".idea"}
SKIP_EXT = {".png", ".jpg", ".jpeg", ".webp", ".gif", ".ico", ".pdf", ".zip",
            ".apk", ".exe", ".jar", ".keystore", ".woff", ".woff2", ".ttf",
            ".otf", ".mp4", ".mp3", ".wav", ".br", ".so", ".bin", ".pyc"}


def main():
    # Windows-консоль CI не всегда UTF-8 — не падаем на кириллице в выводе
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    bad = []
    scanned = 0
    for dp, dirs, files in os.walk(ROOT):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for f in files:
            ext = os.path.splitext(f)[1].lower()
            if ext in SKIP_EXT:
                continue
            path = os.path.join(dp, f)
            try:
                text = open(path, encoding="utf-8", errors="strict").read()
            except (UnicodeDecodeError, OSError):
                continue
            scanned += 1
            for m in PATTERN.finditer(text):
                line = text.count("\n", 0, m.start()) + 1
                rel = os.path.relpath(path, ROOT)
                bad.append(f"{rel}:{line}: {m.group(0)[:6]}… — возможно, живой токен бота")
    if bad:
        print("✗ Найдены подозрительные токены (живые токены в git запрещены):")
        print("\n".join("  " + b for b in bad))
        sys.exit(1)
    print(f"✓ Живых токенов нет (проверено файлов: {scanned})")


if __name__ == "__main__":
    main()
