# Демо «фишек» BotControl: анимация правкой одного сообщения + кубики.
# Формат, который понимает импорт (Скрипты → Импорт настроек из кода).
import random
import time

import telebot
from telebot.types import InlineKeyboardButton, InlineKeyboardMarkup

BOT_TOKEN = "ВСТАВЬ_ТОКЕН"
bot = telebot.TeleBot(BOT_TOKEN)

PREDICTIONS = [
    "🍀 Сегодня повезёт",
    "☕ Сначала кофе, потом подвиги",
    "🚀 Самое время начать",
]

ANIM_ROCKET = [
    "      🚀\n\n\n🌍",
    "\n      🚀\n\n🌍",
    "\n\n      🚀\n🌍",
    "\n\n\n🌍🔥",
]

QUEST_FRAMES = [
    "🌲🌲🌲\n🧍 Ты у входа в лес",
    "🌲🧍🌲\n🌲🌲🌲\nТропинка ведёт вглубь…",
    "🏚️\n🧍\nСтарая хижина. Дверь приоткрыта.",
]


def play_animation(chat_id, frames, delay=0.7, final=None, reply_markup=None, message_id=None, **kw):
    m_id = message_id or bot.send_message(chat_id, frames[0]).message_id
    for frame in frames[1:]:
        time.sleep(delay)
        bot.edit_message_text(frame, chat_id, m_id)
    if final:
        time.sleep(delay)
        bot.edit_message_text(final, chat_id, m_id, reply_markup=reply_markup)


def main_menu():
    kb = InlineKeyboardMarkup()
    kb.row(InlineKeyboardButton("🎰 Слот", callback_data="slot"),
           InlineKeyboardButton("🎲 Кубик", callback_data="dice"))
    kb.row(InlineKeyboardButton("⏳ Загрузка", callback_data="load"),
           InlineKeyboardButton("🌲 Квест", callback_data="quest"))
    return kb


@bot.message_handler(commands=["start"])
def start(msg):
    play_animation(msg.chat.id, "matrix", 0.6, "Добро пожаловать, {user}! Выбирай 👇",
                   text="WAKE UP", reply_markup=main_menu())


@bot.message_handler(commands=["rocket"])
def rocket(msg):
    play_animation(msg.chat.id, ANIM_ROCKET, 0.5, "🛰 Мы на орбите!")


@bot.message_handler(commands=["countdown"])
def countdown(msg):
    m = bot.send_message(msg.chat.id, "3")
    for n in COUNT_FRAMES:
        time.sleep(1)
        bot.edit_message_text(n, msg.chat.id, m.message_id)
    bot.edit_message_text("Поехали! 🚀", msg.chat.id, m.message_id)


COUNT_FRAMES = ["3️⃣", "2️⃣", "1️⃣"]


# Фото-квест: картинка меняется по ходу истории (editMessageMedia),
# кадр без photo: меняет только подпись (editMessageCaption).
TOUR_FRAMES = [
    "photo: https://picsum.photos/id/1018/800/500\nГлава 1. Долина. Ты стоишь у реки.",
    "Слышишь шорох в кустах…",
    "photo: https://picsum.photos/id/1043/800/500\nГлава 2. Лес. Тропа уходит вверх.",
    "photo: https://picsum.photos/id/1036/800/500\nГлава 3. Перевал.",
]


@bot.message_handler(commands=["tour"])
def tour(msg):
    play_animation(msg.chat.id, TOUR_FRAMES, 1.5, "🏁 Ты дошёл, {user}! Конец главы.")


@bot.message_handler(commands=["dart"])
def dart(msg):
    bot.send_dice(msg.chat.id, emoji="🎯")


@bot.callback_query_handler(func=lambda call: True)
def on_callback(call):
    chat_id = call.message.chat.id
    if call.data == "slot":
        play_animation(chat_id, "slot", 0.5, pack=PREDICTIONS,
                       message_id=call.message.message_id, reply_markup=main_menu())
    elif call.data == "dice":
        bot.answer_callback_query(call.id, "Бросаю!")
        bot.send_dice(chat_id, emoji="🎲")
    elif call.data == "load":
        play_animation(chat_id, "progress", 0.7, "✅ Готово", text="Загрузка", loops=2)
    elif call.data == "quest":
        play_animation(chat_id, QUEST_FRAMES, 1.5, "🗝 Внутри — сундук. Конец главы 1.", mono=True)


bot.infinity_polling()
