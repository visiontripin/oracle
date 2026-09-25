#!/usr/bin/env python3
"""
Ассеты BotControl: логотип, аватар бота и слайды презентации.

Один исходник — logo.svg (палитра приложения: #0E1621 / #17212B / #182533 /
#2B5278 / #5288C1 / #F5F6F7 / #7F91A4 / #FBBF24). Слайды — SVG → PNG.

    python3 -m venv /tmp/img && /tmp/img/bin/pip install resvg-py
    /tmp/img/bin/python make_assets.py

Шрифт — DejaVu Sans (есть кириллица). Эмодзи в SVG не рисуются — только
символы DejaVu: ▶ ■ ● ✓ ★ ⚙ ▰ ▱ ◆ ☰.
"""
import os
import re
from xml.sax.saxutils import escape

import resvg_py
from PIL import ImageFont

_FONTS = {}


def measure(s, size, mono=False, bold=False):
    """Ширина строки в px по настоящему шрифту DejaVu."""
    name = "DejaVuSansMono.ttf" if mono else ("DejaVuSans-Bold.ttf" if bold else "DejaVuSans.ttf")
    key = (name, size)
    if key not in _FONTS:
        _FONTS[key] = ImageFont.truetype(os.path.join("/usr/share/fonts/truetype/dejavu", name), size)
    return _FONTS[key].getlength(s)


def wrap(s, size, max_w):
    words, lines, cur = s.split(" "), [], ""
    for w in words:
        t = (cur + " " + w).strip()
        if cur and measure(t, size) > max_w:
            lines.append(cur)
            cur = w
        else:
            cur = t
    return lines + [cur]

HERE = os.path.dirname(os.path.abspath(__file__))
BG, PANEL, BUBBLE, BLUE, BLUE_L, TEXT, MUTED, AMBER, DIVIDER = (
    "#0E1621", "#17212B", "#182533", "#2B5278", "#5288C1", "#F5F6F7", "#7F91A4", "#FBBF24", "#33475A")
FONT = "DejaVu Sans"
FONT_DIRS = ["/usr/share/fonts/truetype/dejavu", "/usr/share/fonts"]

LOGO = open(os.path.join(HERE, "logo.svg"), encoding="utf-8").read()


def logo_inner(svg: str) -> str:
    """Содержимое logo.svg без корневого тега — для вставки во вложенный <svg>."""
    return re.sub(r"^.*?<svg[^>]*>|</svg>\s*$", "", svg, flags=re.S)


def render(svg: str, path: str, w: int, h: int):
    data = resvg_py.svg_to_bytes(svg_string=svg, width=w, height=h, font_dirs=FONT_DIRS,
                                 sans_serif_family=FONT)
    with open(path, "wb") as f:
        f.write(bytes(data))
    print("→", os.path.relpath(path, HERE), f"{w}×{h}")


def text(x, y, s, size=30, color=TEXT, weight="normal", anchor="start", family=FONT):
    return (f'<text x="{x}" y="{y}" font-family="{family}" font-size="{size}" fill="{color}" '
            f'font-weight="{weight}" text-anchor="{anchor}">{escape(s)}</text>')


# ------------------------------------------------------------------
# макет чата Telegram справа на слайде
# ------------------------------------------------------------------

def phone(items, x=790, y=110, w=410, h=520, title="BotControl Demo"):
    out = [f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="34" fill="{PANEL}" stroke="{DIVIDER}" stroke-width="2"/>',
           f'<path d="M{x} {y+34} a34 34 0 0 1 34 -34 h{w-68} a34 34 0 0 1 34 34 v44 h-{w} z" fill="{BUBBLE}"/>',
           f'<svg x="{x+18}" y="{y+14}" width="46" height="46" viewBox="0 0 512 512">{logo_inner(LOGO)}</svg>',
           text(x + 76, y + 36, title, 20, TEXT, "bold"),
           text(x + 76, y + 60, "бот · онлайн", 15, BLUE_L)]
    cy = y + 104
    for kind, val in items:
        if kind in ("in", "out", "code"):
            lines = val if isinstance(val, list) else [val]
            size = 17 if kind == "code" else 19
            lh = 24 if kind == "code" else 27
            bw = min(w - 40, max(110, int(max(measure(l, size, kind == "code") for l in lines)) + 34))
            bh = len(lines) * lh + 20
            bx = x + w - 20 - bw if kind == "out" else x + 20
            fill = BLUE if kind == "out" else ("#0B121A" if kind == "code" else BUBBLE)
            out.append(f'<rect x="{bx}" y="{cy}" width="{bw}" height="{bh}" rx="16" fill="{fill}"/>')
            fam = "DejaVu Sans Mono" if kind == "code" else FONT
            for i, l in enumerate(lines):
                col = "#9FE3A2" if kind == "code" and l.strip().startswith(("@", "def")) else TEXT
                out.append(text(bx + 16, cy + 30 + i * lh - (4 if kind == "code" else 0), l, size, col, family=fam))
            cy += bh + 12
        elif kind == "photo":  # фото-кадр в чате
            out.append(f'<rect x="{x+20}" y="{cy}" width="{w-110}" height="136" rx="16" fill="{BLUE}"/>')
            out.append(f'<svg x="{x+20+(w-110)//2-55}" y="{cy+12}" width="96" height="96" viewBox="0 0 512 512">{logo_inner(LOGO)}</svg>')
            out.append(text(x + 36, cy + 126, val, 15, TEXT))
            cy += 148
        elif kind == "btns":
            for row in val:
                n = len(row)
                gap = 8
                bw = (w - 40 - gap * (n - 1)) / n
                for i, label in enumerate(row):
                    bx = x + 20 + i * (bw + gap)
                    out.append(f'<rect x="{bx:.0f}" y="{cy}" width="{bw:.0f}" height="40" rx="10" fill="{BLUE}" fill-opacity="0.55"/>')
                    out.append(text(bx + bw / 2, cy + 26, label, 16, TEXT, anchor="middle"))
                cy += 48
            cy += 4
        elif kind == "gap":
            cy += val
    return "\n".join(out)


def slide(n, total, title_lines, bullets, chat, footer="github.com/visiontripin/oracle"):
    W, H = 1280, 720
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}">',
             '<defs><radialGradient id="glow" cx="0.78" cy="0.45" r="0.6">'
             f'<stop offset="0" stop-color="{BLUE}" stop-opacity="0.55"/><stop offset="1" stop-color="{BG}" stop-opacity="0"/>'
             '</radialGradient></defs>',
             f'<rect width="{W}" height="{H}" fill="{BG}"/>',
             f'<rect width="{W}" height="{H}" fill="url(#glow)"/>',
             f'<svg x="70" y="48" width="64" height="64" viewBox="0 0 512 512">{logo_inner(LOGO)}</svg>',
             text(148, 92, "BotControl", 30, TEXT, "bold"),
             text(1210, 90, f"{n} / {total}", 22, MUTED, anchor="end")]
    y = 215 if len(title_lines) == 1 else 195
    tsize = 58
    while tsize > 40 and max(measure(t, tsize, bold=True) for t in title_lines) > 670:
        tsize -= 2
    for i, t in enumerate(title_lines):
        parts.append(text(80, y + i * 72, t, tsize, TEXT, "bold"))
    y += (len(title_lines) - 1) * 72 + 30
    parts.append(f'<rect x="80" y="{y}" width="120" height="8" rx="4" fill="{AMBER}"/>')
    y += 70
    for b in bullets:
        lines = [l for part in (b if isinstance(b, list) else [b]) for l in wrap(part, 29, 650)]
        parts.append(f'<circle cx="92" cy="{y-10}" r="8" fill="{AMBER}"/>')
        for j, l in enumerate(lines):
            parts.append(text(118, y + j * 38, l, 29, TEXT))
        y += 38 * len(lines) + 26
    parts.append(phone(chat))
    parts.append(text(80, 680, footer, 20, MUTED))
    parts.append("</svg>")
    return "\n".join(parts)


SLIDES = [
    ("01-cover", ["Telegram-боты", "прямо с телефона"],
     ["Без сервера и VPS: бот живёт в приложении",
      "Команды, кнопки, расписание, анимации",
      "ИИ — на устройстве, без облака",
      "Импорт и экспорт кода бота на Python"],
     [("in", ["Симуляция активирована,", "Нео. Выбирай раздел:"]),
      ("btns", [["☰ Функции", "▶ Как начать"], ["★ GitHub", "⬇ Скачать APK"]])]),
    ("02-rules", ["Команды и кнопки"],
     ["Команда, фраза или кнопка → ответ",
      "Текст, набор фраз, скрипт JS или ИИ",
      ["Inline-кнопки: всплывашки, ссылки,", "правка сообщения на месте"],
      "Клавиатура чата и меню команд"],
     [("out", "/start"),
      ("in", ["Привет, Нео!", "Нажми «Запуск», чтобы включить", "напоминания."]),
      ("btns", [["▶ Запуск", "■ Стоп"]]),
      ("gap", 4),
      ("in", "✓ Запущено")]),
    ("03-anim", ["Анимации"],
     ["Сообщение оживает кадр за кадром",
      ["10 эффектов: прогресс, спиннер,", "Матрица, слот-машина, отсчёт…"],
      "Свои кадры: ASCII-арт и флипбук",
      "Фото-квест: картинка меняется"],
     [("out", "/progress"),
      ("in", ["Загрузка", "▰▰▰▰▰▰▱▱▱▱ 60%"]),
      ("out", "/slot"),
      ("in", "| ★  ◆  7 |"),
      ("photo", "Глава 2. Лес. Тропа вверх.")]),
    ("04-schedule", ["Расписание"],
     ["Напоминания по дням недели",
      ["Будни, пятница, выходные —", "у каждой зоны своё время"],
      "Кнопки под напоминанием",
      "Посты в канал по расписанию"],
     [("in", "08:55  ПЕРЕКУР"),
      ("btns", [["Покурил", "Остался здоровым"]]),
      ("in", ["Красавчик. Лёгкие пока", "не подали в суд."]),
      ("in", "11:30  ОБЕД"),
      ("in", "15:55  ПЕРЕКУР и до завтра!")]),
    ("05-import", ["Импорт и экспорт"],
     ["Вставь код бота на Python — приложение разложит его по настройкам",
      "Экспорт обратно в рабочий .py",
      ["Промт для нейросети:", "бот по описанию за минуту"]],
     [("code", ['@bot.message_handler(', '    commands=["start"])', 'def cmd_start(msg):',
                '    play_animation(msg.chat.id,', '        "matrix", 0.6,', '        "Привет!")']),
      ("in", ["✓ Команд: 2  Кнопок: 4", "✓ Событий: 13  Наборов: 2"])]),
    ("06-ai", ["ИИ на устройстве"],
     ["Локальная модель — без облака",
      ["Только по сценарию: /chat", "и правила с действием «ИИ»"],
      "Характер задаётся промтом",
      "Токены — в зашифрованном хранилище"],
     [("out", "/chat кто ты?"),
      ("in", "Я неизбежность."),
      ("out", "/chat а зачем?"),
      ("in", ["Потому что вы, люди,", "всегда задаёте вопросы."])]),
    ("07-listings", ["Объявления и канал"],
     [["Визард: описание → контакт →", "фото → предпросмотр → пост"],
      "Мои объявления: поднять, удалить",
      "Публикация в канал без дублей",
      "Журнал и проблемы бота на экране"],
     [("out", "Разместить объявление"),
      ("in", ["Шаг 1 из 4 — опиши вещь:", "что это, состояние, цена."]),
      ("out", "Велосипед, 5000 ₽"),
      ("in", "✓ Опубликовано в канале"),
      ("btns", [["Поднять", "Удалить"]])]),
    ("08-start", ["Как начать"],
     ["1. Скачай APK с GitHub (Релизы)",
      "2. @BotFather → /newbot → токен",
      "3. Приложение → «+ Добавить бота»",
      "4. Правила, импорт кода или промт",
      "5. ▶ Запустить — бот работает"],
     [("in", ["BotControl — открытый", "проект на GitHub"]),
      ("btns", [["★ GitHub", "⬇ Релизы"], ["☰ Функции", "▶ В начало"]])]),
]


def main():
    out_dir = os.path.join(HERE, "slides")
    os.makedirs(out_dir, exist_ok=True)
    # логотип и аватар
    render(LOGO, os.path.join(HERE, "logo-512.png"), 512, 512)
    render(LOGO, os.path.join(HERE, "logo-1024.png"), 1024, 1024)
    # аватар бота: без скруглённых углов (Telegram сам обрежет по кругу)
    avatar = LOGO.replace('rx="112"', 'rx="0"')
    render(avatar, os.path.join(HERE, "avatar-640.png"), 640, 640)
    total = len(SLIDES)
    for i, (name, title, bullets, chat) in enumerate(SLIDES, 1):
        svg = slide(i, total, title, bullets, chat)
        render(svg, os.path.join(out_dir, name + ".png"), 1280, 720)
    # копия для веб-страницы (GitHub Pages из /docs)
    import shutil
    web = os.path.join(HERE, "..", "..", "docs", "assets")
    os.makedirs(os.path.join(web, "slides"), exist_ok=True)
    shutil.copy(os.path.join(HERE, "logo.svg"), web)
    shutil.copy(os.path.join(HERE, "logo-512.png"), web)
    for name, *_ in SLIDES:
        shutil.copy(os.path.join(out_dir, name + ".png"), os.path.join(web, "slides"))
    print("→ docs/assets (logo + slides)")


if __name__ == "__main__":
    main()
