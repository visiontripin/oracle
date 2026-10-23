# ============================================================
# BotControl — бот-презентация приложения (@AppBotcontrol_bot)
#
# Импорт в приложение: Скрипты → Импорт настроек из кода →
# Выбрать файл / вставить → Разобрать → Применить к боту → ▶ Запустить.
# Файл также запускается как обычный бот:
#   pip install pyTelegramBotAPI && python presentation-bot-ready.py
#
# Что внутри:
#   • /start — обложка-слайд, подпись «грузится» прогресс-баром, в конце —
#     главное меню кнопками под той же картинкой;
#   • кнопки меню листают слайды В ТОМ ЖЕ сообщении (editMessageMedia),
#     меню остаётся под картинкой;
#   • «▶️ Автотур» — все 8 слайдов сами по очереди;
#   • демо-команды «фишек»: /progress /matrix /slot /typewriter /countdown
#     /spinner /quest /dice /howto /links /help;
#   • кнопки-ссылки: GitHub и страница релизов (скачать APK).
#
# Слайды лежат в репозитории (tg-bot-control/branding/slides), ссылки
# закреплены за тегом релиза — картинки не поменяются «под ногами».
# Аватар бота: @BotFather → /setuserpic → branding/avatar-640.png.
# ============================================================
import random
import time

import telebot
from telebot.types import (BotCommand, InlineKeyboardButton, InlineKeyboardMarkup,
                           InputMediaPhoto)

BOT_TOKEN = "ВСТАВЬ_ТОКЕН_НЕ_ИМПОРТИРУЕТСЯ"  # в приложении токен уже есть
bot = telebot.TeleBot(BOT_TOKEN)

GITHUB_URL = "https://github.com/visiontripin/oracle"
RELEASES_URL = "https://github.com/visiontripin/oracle/releases"


# ---------- анимация: одно сообщение правится кадр за кадром ----------
# Кадр «photo: URL\nподпись» — картинка (sendPhoto → editMessageMedia),
# кадр без photo: под картинкой меняет только подпись.
def _preset_frames(name, text=""):
    if name == "progress":
        return [f"{text}\n{'▰' * i}{'▱' * (10 - i)} {i * 10}%" for i in range(0, 11, 2)]
    if name == "spinner":
        return [f"{s} {text}" for s in "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"]
    if name == "countdown":
        return [f"{text} {n}…" for n in (3, 2, 1)]
    if name == "typewriter":
        step = max(1, len(text) // 12)
        return [text[:i] + "▌" for i in range(step, len(text) + 1, step)]
    if name == "slot":
        return [" | ".join(random.choice(["🍒", "🍋", "⭐", "🔔", "💎"]) for _ in range(3)) for _ in range(6)]
    if name == "matrix":
        abc = "01ﾊﾐﾋｰｳｼﾅﾓﾆｻﾜﾂｵﾘ"
        rain = ["\n".join(" ".join(random.choice(abc) for _ in range(10)) for _ in range(5)) for _ in range(4)]
        return rain + ([f"\n  {text}"] if text else [])
    return [text or "…"]


def play_animation(chat_id, frames, delay=0.7, final=None, reply_markup=None,
                   message_id=None, loops=1, pack=None, mono=False, text="", user="", **kw):
    """В BotControl вызов читает импорт; здесь — рабочая реализация."""
    if isinstance(frames, str):
        frames = _preset_frames(frames, text)
    frames = [f.replace("{user}", user) for f in list(frames) * max(1, loops)]
    if pack:
        final = random.choice(pack)
    photo = frames[0].startswith("photo:")

    def split(f):
        if f.startswith("photo:"):
            src, _, cap = f.partition("\n")
            return src[6:].strip(), cap
        return None, f

    m_id = message_id
    for i, frame in enumerate(frames):
        src, cap = split(frame)
        last = i == len(frames) - 1 and not final
        kb = reply_markup if last else None
        if m_id is None:
            m = (bot.send_photo(chat_id, src, caption=cap, reply_markup=kb) if photo
                 else bot.send_message(chat_id, cap, reply_markup=kb, parse_mode="HTML" if mono else None))
            m_id = m.message_id
            continue
        if i > 0 or message_id is None:
            time.sleep(delay)
        try:
            if src:
                bot.edit_message_media(InputMediaPhoto(src, caption=cap), chat_id, m_id, reply_markup=kb)
            elif photo:
                bot.edit_message_caption(cap, chat_id, m_id, reply_markup=kb)
            else:
                bot.edit_message_text(cap, chat_id, m_id, reply_markup=kb)
        except Exception:
            pass  # «message is not modified» и т.п.
    if final:
        time.sleep(delay)
        final = final.replace("{user}", user)
        if photo:
            bot.edit_message_caption(final, chat_id, m_id, reply_markup=reply_markup)
        else:
            bot.edit_message_text(final, chat_id, m_id, reply_markup=reply_markup)


# ---------- слайды ----------
SLIDE_COVER = [
    "photo: https://raw.githubusercontent.com/visiontripin/oracle/botcontrol-v1.6.2-code29/tg-bot-control/branding/slides/01-cover.png\n"
    "🤖 BotControl — Telegram-боты прямо с телефона.\n\n"
    "Без сервера и VPS: бот живёт в приложении на Android. "
    "Команды, кнопки, расписание, анимации, ИИ на устройстве.\n\n"
    "Листай разделы кнопками 👇",
]

SLIDE_RULES = [
    "photo: https://raw.githubusercontent.com/visiontripin/oracle/botcontrol-v1.6.2-code29/tg-bot-control/branding/slides/02-rules.png\n"
    "🧩 Команды и кнопки\n\n"
    "• Правило: команда, фраза или кнопка → ответ.\n"
    "• Ответ: текст, набор фраз (случайный), скрипт JS, анимация или ИИ.\n"
    "• Inline-кнопки: всплывашки, ссылки, правка сообщения на месте — как в этом меню.\n"
    "• Клавиатура чата и меню команд — тоже кнопками.\n\n"
    "Где: приложение → бот → «Правила».",
]

SLIDE_ANIM = [
    "photo: https://raw.githubusercontent.com/visiontripin/oracle/botcontrol-v1.6.2-code29/tg-bot-control/branding/slides/03-anim.png\n"
    "🎞 Анимации\n\n"
    "Сообщение оживает кадр за кадром — бот правит его сам.\n"
    "• 10 эффектов: прогресс, спиннер, точки, отсчёт, печатная машинка, Матрица, слот, луна, часы, сердце.\n"
    "• Свои кадры: ASCII-арт и флипбук.\n"
    "• Фото-квест: картинка меняется — как эти слайды.\n\n"
    "Попробуй: /progress /matrix /slot /typewriter /countdown /quest",
]

SLIDE_SCHEDULE = [
    "photo: https://raw.githubusercontent.com/visiontripin/oracle/botcontrol-v1.6.2-code29/tg-bot-control/branding/slides/04-schedule.png\n"
    "⏰ Расписание\n\n"
    "• Напоминания по дням недели: будни, пятница, выходные — своё время.\n"
    "• Под напоминанием — кнопки с ответами.\n"
    "• «Запуск / Стоп» — кнопками прямо в Telegram.\n"
    "• Посты в канал по расписанию.\n\n"
    "Где: бот → «Расписание».",
]

SLIDE_IMPORT = [
    "photo: https://raw.githubusercontent.com/visiontripin/oracle/botcontrol-v1.6.2-code29/tg-bot-control/branding/slides/05-import.png\n"
    "📥 Импорт и экспорт\n\n"
    "• Вставь код бота на Python (telebot / aiogram) — приложение разложит его по правилам, кнопкам и расписанию.\n"
    "• Экспорт обратно в рабочий .py.\n"
    "• Промт для нейросети: опиши бота словами — получи готовый код для импорта.\n\n"
    "Этот бот сделан именно так: presentation-bot-ready.py.",
]

SLIDE_AI = [
    "photo: https://raw.githubusercontent.com/visiontripin/oracle/botcontrol-v1.6.2-code29/tg-bot-control/branding/slides/06-ai.png\n"
    "🧠 ИИ на устройстве\n\n"
    "• Локальная модель прямо в телефоне — никакого облака.\n"
    "• Включается только по сценарию: /chat или правило с действием «ИИ».\n"
    "• Характер задаётся промтом.\n"
    "• Токены ботов — в зашифрованном хранилище, в экспорт не попадают.",
]

SLIDE_LISTINGS = [
    "photo: https://raw.githubusercontent.com/visiontripin/oracle/botcontrol-v1.6.2-code29/tg-bot-control/branding/slides/07-listings.png\n"
    "📢 Объявления и канал\n\n"
    "• Визард: описание → контакт → фото → предпросмотр → пост в канал.\n"
    "• «Мои объявления»: поднять, удалить.\n"
    "• Публикация без дублей.\n"
    "• Журнал и проблемы бота — на экране приложения.",
]

SLIDE_HOWTO = [
    "photo: https://raw.githubusercontent.com/visiontripin/oracle/botcontrol-v1.6.2-code29/tg-bot-control/branding/slides/08-start.png\n"
    "🚀 Как начать\n\n"
    "1. Скачай APK — кнопка «⬇️ Скачать APK» ниже.\n"
    "2. @BotFather → /newbot → скопируй токен.\n"
    "3. Приложение → «+ Добавить бота» → вставь токен.\n"
    "4. Настрой правила, импортируй код или сгенерируй его промтом.\n"
    "5. ▶ Запустить — бот работает, пока работает телефон.",
]

# /start: обложка, подпись «грузится», затем меню
INTRO_FRAMES = [
    "photo: https://raw.githubusercontent.com/visiontripin/oracle/botcontrol-v1.6.2-code29/tg-bot-control/branding/slides/01-cover.png\n"
    "Загружаю презентацию…\n▰▱▱▱▱▱▱▱▱▱ 10%",
    "Загружаю презентацию…\n▰▰▰▰▱▱▱▱▱▱ 40%",
    "Загружаю презентацию…\n▰▰▰▰▰▰▰▱▱▱ 70%",
    "Загружаю презентацию…\n▰▰▰▰▰▰▰▰▰▰ 100%",
]

# «▶️ Автотур»: все слайды по очереди
TOUR_FRAMES = [
    "photo: https://raw.githubusercontent.com/visiontripin/oracle/botcontrol-v1.6.2-code29/tg-bot-control/branding/slides/01-cover.png\n"
    "▶️ Автотур · 1/8 · BotControl",
    "photo: https://raw.githubusercontent.com/visiontripin/oracle/botcontrol-v1.6.2-code29/tg-bot-control/branding/slides/02-rules.png\n"
    "▶️ Автотур · 2/8 · Команды и кнопки",
    "photo: https://raw.githubusercontent.com/visiontripin/oracle/botcontrol-v1.6.2-code29/tg-bot-control/branding/slides/03-anim.png\n"
    "▶️ Автотур · 3/8 · Анимации",
    "photo: https://raw.githubusercontent.com/visiontripin/oracle/botcontrol-v1.6.2-code29/tg-bot-control/branding/slides/04-schedule.png\n"
    "▶️ Автотур · 4/8 · Расписание",
    "photo: https://raw.githubusercontent.com/visiontripin/oracle/botcontrol-v1.6.2-code29/tg-bot-control/branding/slides/05-import.png\n"
    "▶️ Автотур · 5/8 · Импорт и экспорт",
    "photo: https://raw.githubusercontent.com/visiontripin/oracle/botcontrol-v1.6.2-code29/tg-bot-control/branding/slides/06-ai.png\n"
    "▶️ Автотур · 6/8 · ИИ на устройстве",
    "photo: https://raw.githubusercontent.com/visiontripin/oracle/botcontrol-v1.6.2-code29/tg-bot-control/branding/slides/07-listings.png\n"
    "▶️ Автотур · 7/8 · Объявления и канал",
    "photo: https://raw.githubusercontent.com/visiontripin/oracle/botcontrol-v1.6.2-code29/tg-bot-control/branding/slides/08-start.png\n"
    "▶️ Автотур · 8/8 · Как начать",
]

# ---------- демо «фишек» ----------
FORTUNES = [
    "🍒 🍒 🍒 — Джекпот! Сегодня твой бот запустится с первого раза.",
    "⭐ ⭐ ⭐ — Звезда на GitHub приносит удачу 😉",
    "💎 💎 💎 — Редкая удача: ни одной ошибки в логе.",
    "🔔 🍋 🍒 — Почти! Крути ещё: /slot",
]

QUEST_FRAMES = [
    "🌲🌲🌲🌲🌲\n🌲  🧍  🌲\n🌲🌲🌲🌲🌲\nТы у входа в лес.",
    "🌲🌲🌲🌲🌲\n🌲🧍    🌲\n🌲🌲  🌲🌲\nТропинка ведёт вглубь…",
    "🌲🌲🌲🌲🌲\n🌲    🏚️🌲\n🌲🌲🧍🌲🌲\nСтарая хижина. Дверь приоткрыта.",
    "  🏚️\n  🧍\n  🗝️\nНа столе — ключ.",
]

HOWTO_FRAMES = [
    "🚀 Как запустить своего бота\n\n1️⃣ Скачай APK с GitHub (Релизы)",
    "🚀 Как запустить своего бота\n\n1️⃣ Скачай APK с GitHub (Релизы)\n2️⃣ @BotFather → /newbot → токен",
    "🚀 Как запустить своего бота\n\n1️⃣ Скачай APK с GitHub (Релизы)\n2️⃣ @BotFather → /newbot → токен\n"
    "3️⃣ Приложение → «+ Добавить бота»",
    "🚀 Как запустить своего бота\n\n1️⃣ Скачай APK с GitHub (Релизы)\n2️⃣ @BotFather → /newbot → токен\n"
    "3️⃣ Приложение → «+ Добавить бота»\n4️⃣ Правила / импорт кода / промт",
]


# ---------- клавиатуры ----------
def main_menu():
    kb = InlineKeyboardMarkup()
    kb.row(InlineKeyboardButton("🧩 Команды", callback_data="p_rules"),
           InlineKeyboardButton("🎞 Анимации", callback_data="p_anim"))
    kb.row(InlineKeyboardButton("⏰ Расписание", callback_data="p_schedule"),
           InlineKeyboardButton("📥 Импорт кода", callback_data="p_import"))
    kb.row(InlineKeyboardButton("🧠 ИИ", callback_data="p_ai"),
           InlineKeyboardButton("📢 Объявления", callback_data="p_listings"))
    kb.row(InlineKeyboardButton("▶️ Автотур", callback_data="p_tour"),
           InlineKeyboardButton("🚀 Как начать", callback_data="p_howto"))
    kb.row(InlineKeyboardButton("🎲 Кубик", callback_data="p_dice"),
           InlineKeyboardButton("🏠 В начало", callback_data="p_home"))
    kb.row(InlineKeyboardButton("⭐ GitHub", url=GITHUB_URL),
           InlineKeyboardButton("⬇️ Скачать APK", url=RELEASES_URL))
    return kb


def links_menu():
    kb = InlineKeyboardMarkup()
    kb.row(InlineKeyboardButton("⭐ GitHub", url=GITHUB_URL))
    kb.row(InlineKeyboardButton("⬇️ Релизы / APK", url=RELEASES_URL))
    kb.row(InlineKeyboardButton("📘 README", url="https://github.com/visiontripin/oracle/tree/main/tg-bot-control"))
    return kb


# ---------- команды ----------
@bot.message_handler(commands=["start"])
def cmd_start(msg):
    play_animation(msg.chat.id, INTRO_FRAMES, 0.8,
                   "👋 Привет, {user}! Это BotControl — Telegram-боты прямо с телефона.\n\n"
                   "Этот бот — живая демонстрация: всё, что ты видишь, настроено в приложении "
                   "кнопками, без сервера. Листай разделы 👇",
                   reply_markup=main_menu())


@bot.message_handler(commands=["tour"])
def cmd_tour(msg):
    play_animation(msg.chat.id, TOUR_FRAMES, 3.0,
                   "🏁 Это все разделы, {user}. Выбирай, что посмотреть подробнее 👇",
                   reply_markup=main_menu())


@bot.message_handler(commands=["help"])
def cmd_help(msg):
    bot.send_message(msg.chat.id,
                     "📖 Что умеет эта презентация\n\n"
                     "/start — обложка и меню разделов\n"
                     "/tour — автотур по всем слайдам\n"
                     "/howto — как запустить своего бота\n"
                     "/links — GitHub и скачивание APK\n\n"
                     "🎞 Анимации:\n"
                     "/progress — прогресс-бар\n"
                     "/spinner — спиннер\n"
                     "/matrix — цифровой дождь\n"
                     "/slot — слот-машина\n"
                     "/typewriter — печатная машинка\n"
                     "/countdown — обратный отсчёт\n"
                     "/quest — ASCII-квест\n"
                     "/dice — кубик Telegram",
                     reply_markup=main_menu())


@bot.message_handler(commands=["links"])
def cmd_links(msg):
    bot.send_message(msg.chat.id,
                     "🔗 BotControl — открытый проект.\n\n"
                     "Исходники, инструкции и готовые конфиги ботов — на GitHub. "
                     "APK — на странице релизов (самый верхний релиз — свежий).",
                     reply_markup=links_menu())


@bot.message_handler(commands=["howto"])
def cmd_howto(msg):
    play_animation(msg.chat.id, HOWTO_FRAMES, 1.2,
                   "🚀 Как запустить своего бота\n\n1️⃣ Скачай APK с GitHub (Релизы)\n"
                   "2️⃣ @BotFather → /newbot → токен\n3️⃣ Приложение → «+ Добавить бота»\n"
                   "4️⃣ Правила / импорт кода / промт\n5️⃣ ▶ Запустить — готово ✅",
                   mono=False, reply_markup=links_menu())


@bot.message_handler(commands=["progress"])
def cmd_progress(msg):
    play_animation(msg.chat.id, "progress", 0.6, "✅ Готово! Так выглядит прогресс-бар.", text="Загрузка")


@bot.message_handler(commands=["spinner"])
def cmd_spinner(msg):
    play_animation(msg.chat.id, "spinner", 0.6, "✅ Данные получены.", text="Связываюсь с сервером", loops=2)


@bot.message_handler(commands=["matrix"])
def cmd_matrix(msg):
    play_animation(msg.chat.id, "matrix", 0.6, "Wake up, {user}… BotControl имеет тебя 🐇", text="WAKE UP")


@bot.message_handler(commands=["slot"])
def cmd_slot(msg):
    play_animation(msg.chat.id, "slot", 0.5, pack=FORTUNES)


@bot.message_handler(commands=["typewriter"])
def cmd_typewriter(msg):
    play_animation(msg.chat.id, "typewriter", 0.5, "BotControl: твой бот живёт в телефоне. ✍️",
                   text="BotControl: твой бот живёт в телефоне.")


@bot.message_handler(commands=["countdown"])
def cmd_countdown(msg):
    play_animation(msg.chat.id, "countdown", 1.0, "🚀 Поехали!", text="Старт через")


@bot.message_handler(commands=["quest"])
def cmd_quest(msg):
    play_animation(msg.chat.id, QUEST_FRAMES, 1.5, "🗝 Ты нашёл ключ! Конец главы 1.\nЕщё: /tour", mono=True)


@bot.message_handler(commands=["dice"])
def cmd_dice(msg):
    bot.send_dice(msg.chat.id, emoji="🎲")


# ---------- кнопки: слайды листаются в том же сообщении ----------
@bot.callback_query_handler(func=lambda call: True)
def on_callback(call):
    chat_id = call.message.chat.id
    mid = call.message.message_id
    if call.data == "p_rules":
        play_animation(chat_id, SLIDE_RULES, 0.5, message_id=mid, reply_markup=main_menu())
    elif call.data == "p_anim":
        play_animation(chat_id, SLIDE_ANIM, 0.5, message_id=mid, reply_markup=main_menu())
    elif call.data == "p_schedule":
        play_animation(chat_id, SLIDE_SCHEDULE, 0.5, message_id=mid, reply_markup=main_menu())
    elif call.data == "p_import":
        play_animation(chat_id, SLIDE_IMPORT, 0.5, message_id=mid, reply_markup=main_menu())
    elif call.data == "p_ai":
        play_animation(chat_id, SLIDE_AI, 0.5, message_id=mid, reply_markup=main_menu())
    elif call.data == "p_listings":
        play_animation(chat_id, SLIDE_LISTINGS, 0.5, message_id=mid, reply_markup=main_menu())
    elif call.data == "p_howto":
        play_animation(chat_id, SLIDE_HOWTO, 0.5, message_id=mid, reply_markup=main_menu())
    elif call.data == "p_home":
        play_animation(chat_id, SLIDE_COVER, 0.5, message_id=mid, reply_markup=main_menu())
    elif call.data == "p_tour":
        bot.answer_callback_query(call.id, "▶️ Автотур: 8 слайдов")
        play_animation(chat_id, TOUR_FRAMES, 3.0,
                       "🏁 Это все разделы. Выбирай, что посмотреть подробнее 👇",
                       message_id=mid, reply_markup=main_menu())
    elif call.data == "p_dice":
        bot.answer_callback_query(call.id, "🎲 Бросаю!")
        bot.send_dice(chat_id, emoji="🎲")


if __name__ == "__main__":
    bot.set_my_commands([
        BotCommand("start", "Презентация и меню"),
        BotCommand("tour", "Автотур по слайдам"),
        BotCommand("howto", "Как запустить своего бота"),
        BotCommand("links", "GitHub и скачать APK"),
        BotCommand("help", "Все команды"),
        BotCommand("progress", "Анимация: прогресс-бар"),
        BotCommand("matrix", "Анимация: Матрица"),
        BotCommand("slot", "Анимация: слот-машина"),
        BotCommand("quest", "Анимация: ASCII-квест"),
        BotCommand("dice", "Кубик"),
    ])
    bot.infinity_polling()
