# ============================================================
# BotControl — бот ID 2 «Агент Смит / перекур» (оптимизированный)
#
# Импорт в приложение: Скрипты → Импорт настроек из кода →
# Выбрать файл / вставить → Разобрать → Применить к боту.
# Файл также запускается как обычный бот:
#   pip install pyTelegramBotAPI && python smith-bot-ready.py
#
# Что изменено относительно экспорта:
#   • убран блок ИИ (SYSTEM_PROMPT, LLM_*, payload, MAX_HISTORY);
#   • убраны шутки (JOKES) и кнопка «😂 шутка»;
#   • кнопки работают: есть обработчик нажатий (Запуск / Стоп,
#     🚬 Покурил / 💪 Остался здоровым);
#   • кнопки перекура прикреплены к каждому «🔥 ПЕРЕКУР»;
#   • /start — заставка «Матрица» правкой одного сообщения, в конце —
#     приветствие и кнопки;
#   • исправлены &lt; и порванные строки, переносы строк сохранены.
# ============================================================
import html
import random
import threading
import time
from datetime import datetime

import telebot
from telebot.types import BotCommand, InlineKeyboardButton, InlineKeyboardMarkup

BOT_TOKEN = "ВСТАВЬ_ТОКЕН_НЕ_ИМПОРТИРУЕТСЯ"  # в приложении токен уже есть
bot = telebot.TeleBot(BOT_TOKEN)

ANSWER_COOLDOWN = 10        # пауза между ответами, сек
DEFAULT_TYPING_SECONDS = 4  # «печатает…», сек


# ---------- анимация: одно сообщение правится кадр за кадром ----------
def play_animation(chat_id, frames, delay=0.7, final=None, reply_markup=None,
                   message_id=None, loops=1, pack=None, mono=False, user="", **kw):
    """preset= / text= читает BotControl; здесь кадры уже готовым списком."""
    def fmt(s):
        s = s.replace("{user}", user)
        if s.startswith("\n") or s.startswith(" "):
            s = "\u2800" + s  # Telegram обрезает пустые строки в начале
        return ("<pre>" + html.escape(s) + "</pre>") if mono else s

    mode = "HTML" if mono else None
    frames = list(frames) * max(1, loops)
    if pack:
        final = random.choice(pack)
    m_id = message_id
    if m_id is None:
        m_id = bot.send_message(chat_id, fmt(frames[0]), parse_mode=mode).message_id
        frames = frames[1:]
    for frame in frames:
        time.sleep(delay)
        try:
            bot.edit_message_text(fmt(frame), chat_id, m_id, parse_mode=mode)
        except Exception:
            pass  # «message is not modified» и т.п.
    if final:
        time.sleep(delay)
        bot.edit_message_text(final.replace("{user}", user), chat_id, m_id, reply_markup=reply_markup)


# Заставка /start: цифровой дождь → «Wake up…» → приветствие.
ANIM_START = [
    "ﾊ 1 ﾐ 0 ﾋ ｰ ｳ 1\n0 ｼ 1 ﾅ 0 ﾓ ﾆ 0\nｻ 0 ﾜ 1 ﾂ 0 ｵ ﾘ\n1 ｱ 0 ﾎ 1 ﾃ 0 ﾏ",
    "0 ｹ ﾒ 1 ｴ 0 ｶ ﾁ\nﾊ 1 ﾐ 0 ﾋ ｰ ｳ 1\n0 ｼ 1 ﾅ 0 ﾓ ﾆ 0\nｻ 0 ﾜ 1 ﾂ 0 ｵ ﾘ",
    "ﾕ 0 1 ﾗ 0 ｾ 1 ｷ\n0 ｹ ﾒ 1 ｴ 0 ｶ ﾁ\nﾊ 1 ﾐ 0 ﾋ ｰ ｳ 1\n0 ｼ 1 ﾅ 0 ﾓ ﾆ 0",
    "\n  Wake up, {user}…",
    "\n  Матрица имеет тебя.",
    "\n  Следуй за белым кроликом. 🐇",
    "\n  Тук-тук, {user}.",
]


# ---------- команды ----------
@bot.message_handler(commands=["start"])
def cmd_start(msg):
    play_animation(
        msg.chat.id, ANIM_START, 0.8,
        "Симуляция активирована, {user}. 👋\nНажми «Запуск», чтобы включить напоминания.",
        mono=True, reply_markup=kb_start(), user=msg.from_user.first_name or "",
    )


@bot.message_handler(commands=["help"])
def cmd_help(msg):
    bot.send_message(
        msg.chat.id,
        "Кнопки:\n"
        "▶️ Запуск — включить напоминания\n"
        "⏹ Стоп — выключить напоминания\n"
        "\n"
        "Во время перекуров:\n"
        "🚬 Покурил / 💪 Остался здоровым\n"
        "\n"
        "На остальное бот ответит уточняющим вопросом.",
    )


# ---------- кнопки «Запуск / Стоп» ----------
def kb_start():
    markup = InlineKeyboardMarkup()
    markup.row(
        InlineKeyboardButton("▶️ Запуск", callback_data="start"),
        InlineKeyboardButton("⏹ Стоп", callback_data="stop"),
    )
    return markup


# ---------- кнопки перекура (прикреплены к каждому «🔥 ПЕРЕКУР») ----------
def smoke_keyboard():
    markup = InlineKeyboardMarkup()
    markup.row(
        InlineKeyboardButton("🚬 Покурил", callback_data="smoke_done"),
        InlineKeyboardButton("💪 Остался здоровым", callback_data="smoke_healthy"),
    )
    return markup


# ---------- состояние напоминаний ----------
reminders_running = False
user_chat_id = None


# ---------- обработчик нажатий: без него кнопки «немые» ----------
@bot.callback_query_handler(func=lambda call: True)
def on_callback(call):
    global reminders_running, user_chat_id
    chat_id = call.message.chat.id
    if call.data == "start":
        if reminders_running:
            bot.answer_callback_query(call.id, "Уже работает")
            return
        reminders_running = True
        user_chat_id = chat_id
        bot.answer_callback_query(call.id, "Запущено")
        bot.edit_message_text("▶️ Напоминания включены. Нажми «Стоп», чтобы выключить.",
                              chat_id, call.message.message_id, reply_markup=kb_start())
    elif call.data == "stop":
        if not reminders_running:
            bot.answer_callback_query(call.id, "Уже остановлен")
            return
        reminders_running = False
        bot.answer_callback_query(call.id, "Остановлено")
        bot.edit_message_text("⏹ Бот остановлен.\nНажми «Запуск», чтобы возобновить.",
                              chat_id, call.message.message_id, reply_markup=kb_start())
    elif call.data == "smoke_done":
        bot.answer_callback_query(call.id, "🚬 Записал")
        bot.edit_message_text(random.choice(SMOKE_DONE_SARCASM), chat_id, call.message.message_id)
    elif call.data == "smoke_healthy":
        bot.answer_callback_query(call.id, "💪 Записал")
        bot.edit_message_text(random.choice(SMOKE_HEALTHY_SARCASM), chat_id, call.message.message_id)


# ---------- наборы ответов ----------
SMOKE_DONE_SARCASM = [  # 🚬 Покурил
    "🚬 Покурил. Минус лёгкие, плюс пауза.",
    "Отлично. Теперь можно делать вид, что жизнь под контролем.",
    "Записал. Никотин тоже любит учёт.",
    "Покурил? Ну, хотя бы не ипотеку.",
    "Хорошо. Организм уже отправил жалобу, но я её скрыл.",
    "Молодец. Дым рассеялся, проблемы остались.",
    "Принято. Спонсор паузы — тревога.",
    "Покурил — значит, почти медитация. Если медитация пахнет пепельницей.",
    "Зафиксировано. Теперь можно возвращаться к рабочему хаосу.",
    "Ну, хоть что-то в этой жизни сгорело по плану.",
]

SMOKE_HEALTHY_SARCASM = [  # 💪 Остался здоровым
    "💪 Остался здоровым. Организм уже готовит благодарственное письмо.",
    "Красавчик. Лёгкие пока не подали в суд.",
    "Так держать. Даже печень зауважала.",
    "Остался здоровым? Это уже подозрительно взрослый поступок.",
    "Отлично. Смерть немного расстроилась, но переживёт.",
    "Здоровый выбор. Теперь можно гордиться молча, но саркастично.",
    "Молодец. Никотин ждал, а ты его продинамил.",
    "Записал: здоров. Слишком правильно, даже подозрительно.",
    "Хорошо. Организм включил режим 'ну наконец-то'.",
    "Остался без перекура. Характер крепче, чем кофе на работе.",
]

# ---------- уточняющие вопросы (ответ на любое другое сообщение) ----------
CLARIFY_QUESTIONS = [
    "а что?",
    "зачем?",
    "когда?",
    "почему?",
    "кто?",
    "где?",
    "как?",
    "а зачем?",
    "а когда?",
    "а кто?",
    "а где?",
    "а как?",
    "что дальше?",
    "и что?",
    "серьёзно?",
    "это важно?",
    "что случилось?",
    "прямо сейчас?",
]


# ---------- расписание: ежедневно / будни / Пн–Чт / пятница ----------
def get_today_schedule(now):
    wd = now.weekday()  # 0 = Пн … 6 = Вс
    schedule = [
        (0, 0, "🌙 Спокойной ночи"),
        (6, 0, "☀️ Доброе утра"),
        (18, 55, "за Сонькой ❤️"),
    ]
    if wd < 5:
        schedule.extend([
            (8, 55, "🔥 ПЕРЕКУР", smoke_keyboard()),
            (9, 55, "🔥 ПЕРЕКУР", smoke_keyboard()),
            (10, 55, "🔥 ПЕРЕКУР", smoke_keyboard()),
            (11, 30, "🍽 ОБЕД"),
            (12, 55, "🔥 ПЕРЕКУР", smoke_keyboard()),
            (13, 55, "🔥 ПЕРЕКУР", smoke_keyboard()),
            (14, 55, "🔥 ПЕРЕКУР", smoke_keyboard()),
        ])
        if wd == 4:
            schedule.append((15, 55, "🔥 ПЕРЕКУР и до завтра!", smoke_keyboard()))
        else:
            schedule.extend([
                (15, 55, "🔥 ПЕРЕКУР", smoke_keyboard()),
                (16, 55, "до завтра!"),
            ])
    return sorted(schedule, key=lambda item: (item[0], item[1]))


# ---------- всё остальное — уточняющий вопрос ----------
@bot.message_handler(func=lambda m: True)
def fallback(msg):
    bot.send_message(msg.chat.id, random.choice(CLARIFY_QUESTIONS))


# ---------- цикл напоминаний (только при запуске как обычный бот) ----------
def reminder_loop():
    sent = set()
    while True:
        now = datetime.now()
        if reminders_running and user_chat_id:
            for item in get_today_schedule(now):
                key = (now.date(), item[0], item[1])
                if (now.hour, now.minute) == (item[0], item[1]) and key not in sent:
                    sent.add(key)
                    markup = item[3] if len(item) > 3 else None
                    bot.send_message(user_chat_id, item[2], reply_markup=markup)
        time.sleep(20)


if __name__ == "__main__":
    bot.set_my_commands([
        BotCommand("start", "Запустить бота"),
        BotCommand("help", "Помощь"),
    ])
    threading.Thread(target=reminder_loop, daemon=True).start()
    bot.infinity_polling()
