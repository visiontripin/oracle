# ============================================================
# BotControl — демо-бот кофейни «Утро» (проверка импорта v1.5.2)
# ============================================================

BOT_TOKEN = "ТОКЕН_НЕ_ИМПОРТИРУЕТСЯ"

SYSTEM_PROMPT = (
    "РОЛЬ: Ты — бариста кофейни «Утро». Дружелюбный, коротко.\\n"
    "ПРАВИЛА:\\n"
    "1. Язык: русский.\\n"
    "2. Длина: 1-2 предложения.\\n"
    "3. Не обсуждай ничего, кроме кофе."
)

LLM_MAX_TOKENS = 120
MAX_HISTORY = 4
ANSWER_COOLDOWN = 10
DEFAULT_TYPING_SECONDS = 3
payload = {
    "temperature": 0.7,
    "max_tokens": LLM_MAX_TOKENS,
}

MORNING_PHRASES = [
    "Доброе утро! ☕️",
    "Кофе уже заваривается.",
]

# ---------- команды ----------
@bot.message_handler(commands=["start"])
def cmd_start(msg):
    global bot_running
    bot.send_message(msg.chat.id, "Привет, {name}! 👋 Выбирай кнопку ниже.", reply_markup=start_keyboard())

@bot.message_handler(commands=["help"])
def cmd_help(msg):
    bot.send_message(msg.chat.id, "Команды: /start, /menu, /help")

# ---------- кнопки клавиатуры ----------
@bot.message_handler(func=lambda m: m.text == "☕ Меню")
def btn_menu(msg):
    bot.send_message(msg.chat.id, "Эспрессо 150 ₽, капучино 220 ₽, раф 260 ₽", reply_markup=chat_keyboard())

@bot.message_handler(func=lambda m: "цена" in (m.text or "").lower())
def on_price(msg):
    bot.send_message(msg.chat.id, "Эспрессо 150 ₽, капучино 220 ₽")

# ---------- меню под сообщением ----------
def start_keyboard():
    markup = InlineKeyboardMarkup()
    markup.row(InlineKeyboardButton("▶️ Запуск", callback_data="start"),
               InlineKeyboardButton("⏹ Стоп", callback_data="stop"))
    markup.row(InlineKeyboardButton("🎲 Шутка", callback_data="joke"),
               InlineKeyboardButton("🌐 Сайт", url="https://example.com"))
    return markup

def chat_keyboard():
    markup = ReplyKeyboardMarkup(resize_keyboard=True)
    markup.row(KeyboardButton("☕ Меню"), KeyboardButton("🎁 Акции"))
    return markup

bot_running = False

@bot.callback_query_handler(func=lambda call: True)
def on_callback(call):
    global bot_running
    if call.data == "start":
        if bot_running:
            bot.answer_callback_query(call.id, "Уже работает")
            return
        bot.answer_callback_query(call.id, "Запущено")
        bot_running = True
        bot.edit_message_text("▶️ Напоминания включены.", chat_id=call.message.chat.id,
                              message_id=call.message.message_id, reply_markup=start_keyboard())
    elif call.data == "stop":
        bot.answer_callback_query(call.id, "Остановлено")
        bot_running = False
        bot.edit_message_text("⏹ Напоминания выключены.", chat_id=call.message.chat.id,
                              message_id=call.message.message_id, reply_markup=start_keyboard())
    elif call.data == "joke":
        bot.answer_callback_query(call.id, "Лови")
        bot.send_message(call.message.chat.id, random.choice(MORNING_PHRASES))

# ---------- расписание ----------
def get_today_schedule(now):
    schedule = [
        (9, 0, "☕ Доброе утро! Мы открылись."),
    ]
    wd = now.weekday()
    if wd < 5:
        schedule.extend([
            (15, 0, "🍰 Десерт −20% после обеда", start_keyboard()),
        ])
        if wd == 4:
            schedule.append((18, 0, "🎉 Пятничный латте по цене эспрессо"))
        else:
            schedule.extend([
                (18, 0, "🌇 Вечерний кофе со скидкой"),
            ])
    if wd in (5, 6):
        schedule.append((11, 0, "🌴 Выходной: бранч до 14:00"))
    return sorted(schedule, key=lambda item: (item[0], item[1]))

CLARIFY_QUESTIONS = [
    "Какой кофе?",
    "С собой или здесь?",
]
