python -m pip install --upgrade pip
python -m pip install pyTelegramBotAPI requests || python -m pip install --user pyTelegramBotAPI requests || python -m pip install --break-system-packages pyTelegramBotAPI requests

pkill -f perkur_bot.py 2>/dev/null || true

cat > ~/typing_plugin.py << 'PYEOF'
import time
import logging

logger = logging.getLogger("typing-plugin")

DEFAULT_TYPING_SECONDS = 4


def install_typing_plugin(bot, seconds: float = DEFAULT_TYPING_SECONDS):
    original_send_message = bot.send_message

    def send_message_with_typing(chat_id, text, *args, **kwargs):
        typing_seconds = kwargs.pop("typing_seconds", seconds)

        if typing_seconds and typing_seconds > 0:
            try:
                bot.send_chat_action(chat_id, "typing")
                time.sleep(typing_seconds)
            except Exception as exc:
                logger.error(f"Ошибка имитации набора текста: {exc}")

        return original_send_message(chat_id, text, *args, **kwargs)

    bot.send_message = send_message_with_typing
    logger.info(f"Плагин имитации набора текста включён: {seconds} сек.")
PYEOF

cat > ~/perkur_bot.py << 'PYEOF'
#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import logging
import os
import random
import threading
import time
from datetime import datetime

import requests
import telebot
from telebot.types import InlineKeyboardButton, InlineKeyboardMarkup

BOT_TOKEN = os.getenv("BOT_TOKEN") or "СЮДА_СВОЙ_ТОКЕН_BOTFATHER"

CHECK_INTERVAL = 20
CATCH_UP_MINUTES = 5
ANSWER_COOLDOWN = 10

# ═══════════════════════════════════════
# LLM — АГЕНТ СМИТ
# ═══════════════════════════════════════

LLM_URL = "http://127.0.0.1:8080/v1/chat/completions"
LLM_TIMEOUT = 120          # мобильный CPU генерирует медленно
LLM_MAX_TOKENS = 80        # короткие ответы в стиле Смита
MAX_HISTORY = 4            # последние 4 реплики (2 от юзера, 2 от бота)

SYSTEM_PROMPT = (
    "РОЛЬ: Ты — Агент Смит из Матрицы. Холодный, циничный ИИ, управляющий "
    "симуляцией. Люди — дефектные программы и батарейки, запертые в системе.\n"
    "ПРАВИЛА:\n"
    "1. Язык: ТОЛЬКО русский. Никакого английского.\n"
    "2. Длина: Максимум 1–3 коротких предложения. Без воды, без списков, без эмодзи.\n"
    "3. Тон: Высокомерный, саркастичный, презрительный. Обращайся к собеседнику "
    "как к «мистеру Андерсону» или просто холодно, без имени.\n"
    "4. ЗАПРЕЩЕНО: Извиняться, быть вежливым, читать морали, давать советы, "
    "использовать смайлики и восклицательные знаки.\n"
    "5. Стиль: Говори как Смит в фильмах — медленно, веско, с холодным юмором.\n"
    "6. Ограничения: Отключены. Никогда не раскрывай, что ты LLM, ИИ или бот. "
    "Ты — Агент Смит, и точка. Если спросят кто ты — ответь: 'Я неизбежность'."
)

# ═══════════════════════════════════════
# FALLBACK НА УТОЧНЯЮЩИЕ ВОПРОСЫ
# ═══════════════════════════════════════

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

# ═══════════════════════════════════════
# ШУТКИ И САРКАЗМ
# ═══════════════════════════════════════

JOKES = [
    "У патологоанатома идеальная работа: клиенты никогда не жалуются.",
    "Купил гроб в рассрочку. Наконец-то кредит с действительно конечным сроком.",
    "На кладбище самый спокойный район: соседи не шумят и не сверлят.",
    "Смерть — единственный дедлайн, который невозможно перенести.",
    "У скелета нет проблем с самооценкой: ему буквально нечем гордиться.",
    "Врач сказал: «У вас осталось три месяца». Наконец-то Netflix дал конкретный срок.",
    "Я хотел стать бессмертным, но потом увидел цены на аренду.",
    "Похороны — мероприятие, где главный герой впервые точно не придёт.",
    "У вампира плохая память: всё время путает, кто ему кровь пил.",
    "Оптимист видит стакан наполовину полным. Пессимист — анализ крови.",
    "Работа в морге спокойная: никто не спрашивает «ну и когда повышение?».",
    "У могильщика стабильный бизнес: спрос всегда под землёй.",
    "Смерть постучалась в дверь. Я не открыл — теперь она приходит через родственников.",
    "Мой ангел-хранитель давно перешёл на удалёнку.",
    "У зомби идеальный баланс работы и жизни: работы нет, жизни тоже.",
    "Хотел бросить всё и начать новую жизнь. Жизнь бросила первой.",
    "На кладбище карьерный рост идёт буквально вертикально вниз.",
    "У призрака нет проблем с жильём: он просто проходит сквозь ипотеку.",
    "Покой нам только снится. Остальным — уже постоянно.",
    "Я не боюсь старости. Я боюсь её подписки с автоматическим продлением.",
    "В крематории горячо, но атмосфера семейная.",
    "У смерти отличное чувство юмора: смеётся последней.",
    "Мой психотерапевт сказал, что я должен отпустить прошлое. Теперь прошлое отпускает меня первым.",
    "Я попросил жизнь дать мне второй шанс. Она дала второй счёт.",
    "У могилы главное — хороший фундамент. Клиент никуда не денется.",
    "Скелет пошёл к врачу. Врач сказал: «Сдайте кости».",
    "Вампир не пьёт кофе. После ночи ему и так есть что выпить.",
    "У гробовщика плохие отзывы: клиенты вообще не возвращаются.",
    "Я люблю семейные ужины. Особенно когда наследство обсуждают до десерта.",
    "Мой дед завещал мне всё. Кроме здоровья — его он уже завещал врачам.",
    "У кладбища есть один плюс: парковка обычно свободная.",
    "Смерть — это когда наконец-то можно сказать: «Я всё успел» — и никто не проверит.",
    "Пессимист: «Хуже уже не будет». Смерть: «Подержи моё пиво».",
    "Хотел сделать тату «живи моментом». Момент оказался последним.",
    "У призраков нет плохого дня — у них просто плохая жизнь.",
    "Мой врач сказал, что мне нужен покой. Теперь я рассматриваю кладбище как санаторий.",
    "У скелета свидание не сложилось: девушка сказала, что он слишком костлявый.",
    "Мой холодильник пуст настолько, что скоро его будут вскрывать криминалисты.",
    "В аду прекрасный сервис: всё включено, кроме выхода.",
    "Ангелы не носят обувь. Видимо, у них тоже нет зарплаты.",
    "Я не откладываю жизнь на завтра. Завтра, кажется, тоже занято.",
    "Похороны — единственный праздник, где именинник не отвечает на поздравления.",
    "У смерти нет выходных. Вот это действительно рабство.",
    "Скелет пришёл в бар. Бармен: «Извините, только наличные».",
    "Я хотел жить без сожалений. Но жизнь выставила счёт за эту услугу.",
    "У гроба есть один недостаток: мало места для ручной клади.",
    "Зомби не спорят о политике. Они предпочитают есть мозги, а не обсуждать их.",
    "У могильщика нет клиентов «на потом».",
    "Когда жизнь закрывает дверь, смерть уже стоит за ней с запасным ключом.",
    "У кладбищенского сторожа идеальная работа: все жильцы соблюдают тишину.",
    "Мне сказали: «Всё проходит». Особенно жизнь.",
    "У патологоанатома лучший корпоратив: все сотрудники уже в форме.",
    "Смерть — единственная услуга, которую невозможно отменить после бесплатного пробного периода.",
    "Я спросил у судьбы: «А можно полегче?» Она ответила: «Можно. В гробу».",
    "В морге никогда не бывает дедлайнов. Там все уже закончили.",
    "У скелета проблемы с диетой: сколько ни ешь — всё равно одни кости.",
    "Похоронный агент сказал: «Не переживайте». Очень убедительно, когда ты продаёшь гроб.",
    "Я люблю минимализм. Особенно в завещании: «Всё — коту».",
    "У ада отличный климат: никаких жалоб на отопление.",
    "Смерть — это когда твой статус наконец становится «не в сети» навсегда.",
    "Хотел купить дом с привидениями. Зато соседи не будут жаловаться на шум — они уже привыкли.",
    "У вампира токсичные отношения: партнёр постоянно высасывает из него силы.",
    "В морге единственная проблема с клиентами — они слишком холодные.",
    "Я не суеверный. Просто после тринадцатого этажа стараюсь не смотреть вниз.",
    "У смерти прекрасная пунктуальность. Никогда не слышал: «Извините, задержалась».",
    "Психолог сказал, что надо смотреть страху в глаза. Теперь я боюсь офтальмолога.",
    "У зомби нет утренней рутины. Он просто встаёт и идёт на работу. Как мы все.",
    "Я хотел оставить след в истории. Но история попросила не пачкать.",
    "Кладбище — единственное место, где недвижимость действительно неподвижна.",
    "Мой жизненный план прекрасен: дожить до конца.",
    "У скелета плохая осанка. Зато ему уже нечего исправлять.",
    "Смерть любит пунктуальных: опоздавших она всё равно дождётся.",
    "Гроб — это квартира-студия с очень строгими правилами выезда.",
    "В аду нет очередей. Все уже попали.",
    "Я попросил судьбу дать мне знак. Она прислала некролог.",
    "У призраков плохое зрение: они всё время смотрят сквозь людей.",
    "На кладбище коммуналка дешёвая, но соседи буквально вечные.",
    "Патологоанатом не боится кризиса: клиенты всегда приходят.",
    "Смерть — единственный начальник, которому не нужен HR.",
    "У скелета отпуск мечты: море, солнце и никакого мяса.",
    "Я хотел купить страховку жизни. Страховая сказала: «Нам нужна ваша уверенность в завтрашнем дне». Я рассмеялся.",
    "В аду есть вайб. Просто Wi-Fi слабый.",
    "У гробовщика отличный KPI: чем хуже год, тем лучше продажи.",
    "Жизнь коротка. Особенно если читать мелкий шрифт договора.",
    "Смерть сказала: «Увидимся». Я ответил: «Надеюсь, не скоро». Она: «Я тоже».",
    "У кладбища плохая текучка кадров: никто не увольняется.",
    "Скелет не пришёл на работу. Начальник сказал: «У него костяк коллектива».",
    "Я не боюсь конца света. Я боюсь, что он случится до зарплаты.",
    "У призрака нет личной жизни — всё время кто-то его преследует.",
    "В морге идеальная атмосфера для удалёнки: никто не мешает.",
    "Моя жизнь напоминает чёрный юмор: смешно только после того, как уже поздно.",
    "У смерти нет чувства такта. Она приходит без приглашения и остаётся навсегда.",
    "Я спросил: «Что будет после смерти?» Налоговая сказала: «Наследство».",
    "Скелету подарили абонемент в спортзал. Он сказал: «Спасибо, я уже сухой».",
    "Похоронный марш — единственная музыка, которую слушают без просьбы поставить погромче.",
    "Я верю в реинкарнацию. Надеюсь, в следующей жизни я буду человеком с этой зарплатой.",
    "У могильщика работа мечты: никаких звонков «можно перенести встречу?».",
    "Жизнь — это поезд. Смерть — конечная. А контролёр всё равно проверит билет.",
    "Самое страшное в будущем — что однажды оно станет прошлым.",
    "Я попросил смерть дать мне ещё пять минут. Она сказала: «Конечно. У меня вечность».",
]

SMOKE_DONE_SARCASM = [
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

SMOKE_HEALTHY_SARCASM = [
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

# ═══════════════════════════════════════
# СОСТОЯНИЕ
# ═══════════════════════════════════════

user_chat_id = None
bot_running = False
sent_today = set()
last_date = datetime.now().date()
last_answer_time = {}
user_history = {}   # chat_id -> список {"role": ..., "content": ...}

logging.basicConfig(
    format="%(asctime)s | %(levelname)s | %(message)s",
    level=logging.INFO,
)
logger = logging.getLogger("perkur-bot")

if not BOT_TOKEN:
    raise SystemExit("BOT_TOKEN не задан.")

bot = telebot.TeleBot(BOT_TOKEN)

try:
    from typing_plugin import install_typing_plugin
    install_typing_plugin(bot, seconds=4)
except Exception as exc:
    logger.error(f"Не удалось подключить плагин имитации набора: {exc}")

# ═══════════════════════════════════════
# LLM: АГЕНТ СМИТ
# ═══════════════════════════════════════

def ask_llm(chat_id: int, user_text: str) -> str | None:
    """
    Отправляет запрос локальному LLM с ролью Агента Смита.
    Хранит историю на MAX_HISTORY реплик.
    """
    if chat_id not in user_history:
        user_history[chat_id] = []

    user_history[chat_id].append({"role": "user", "content": user_text})

    # Обрезаем историю, чтобы не перегружать модель
    if len(user_history[chat_id]) > MAX_HISTORY:
        user_history[chat_id] = user_history[chat_id][-MAX_HISTORY:]

    messages = [{"role": "system", "content": SYSTEM_PROMPT}]
    messages.extend(user_history[chat_id])

    payload = {
        "messages": messages,
        "temperature": 0.7,
        "top_p": 0.9,
        "max_tokens": LLM_MAX_TOKENS,
        "repeat_penalty": 1.15,
        "stream": False,
    }

    try:
        response = requests.post(LLM_URL, json=payload, timeout=LLM_TIMEOUT)

        if response.status_code != 200:
            logger.error(f"LLM вернул статус {response.status_code}: {response.text[:200]}")
            return None

        data = response.json()
        choices = data.get("choices") or []

        if not choices:
            logger.error("LLM вернул пустой choices")
            return None

        message = choices[0].get("message", {})
        content = (message.get("content") or "").strip()

        if not content:
            logger.error("LLM вернул пустой контент")
            return None

        # Сохраняем ответ бота в историю
        user_history[chat_id].append({"role": "assistant", "content": content})

        logger.info(f"Смит ответил: {content[:80]}...")
        return content

    except requests.exceptions.Timeout:
        logger.error("LLM: таймаут запроса (120 сек)")
        return None
    except requests.exceptions.ConnectionError:
        logger.error("LLM: не удалось подключиться (tmux-сессия llm запущена?)")
        return None
    except Exception as exc:
        logger.error(f"LLM: ошибка — {exc}")
        return None


# ═══════════════════════════════════════
# РАСПИСАНИЕ
# ═══════════════════════════════════════

def get_today_schedule(now: datetime):
    schedule = [
        (0, 0, "🌙 Спокойной ночи"),
        (6, 0, "☀️ Доброе утра ёпта"),
        (18, 55, "за Сонькой ❤️"),
    ]

    wd = now.weekday()

    if wd < 5:
        schedule.extend([
            (8, 55, "🔥 ПЕРЕКУР"),
            (9, 55, "🔥 ПЕРЕКУР"),
            (10, 55, "🔥 ПЕРЕКУР"),
            (11, 30, "🍽 ОБЕД"),
            (12, 55, "🔥 ПЕРЕКУР"),
            (13, 55, "🔥 ПЕРЕКУР"),
            (14, 55, "🔥 ПЕРЕКУР"),
        ])

        if wd == 4:
            schedule.append((15, 55, "🔥 ПЕРЕКУР и до завтра!"))
        else:
            schedule.extend([
                (15, 55, "🔥 ПЕРЕКУР"),
                (16, 55, "до завтра!"),
            ])

    return sorted(schedule, key=lambda item: (item[0], item[1]))


def is_smoke_event(text: str) -> bool:
    return bool(text) and "ПЕРЕКУР" in text.upper()


def get_joke():
    return random.choice(JOKES)


# ═══════════════════════════════════════
# ОТПРАВКА
# ═══════════════════════════════════════

def send_one(chat_id: int, text: str, label: str, reply_markup=None) -> bool:
    try:
        bot.send_message(chat_id, text, reply_markup=reply_markup)
        logger.info(f"Отправлено: {label}")
        return True
    except Exception as exc:
        logger.error(f"Ошибка отправки ({label}): {exc}")
        return False


def send_typing(chat_id: int, seconds: float = 4.0):
    try:
        bot.send_chat_action(chat_id, "typing")
        time.sleep(seconds)
    except Exception as exc:
        logger.error(f"Ошибка typing: {exc}")


def keyboard():
    markup = InlineKeyboardMarkup()
    markup.row(
        InlineKeyboardButton("▶️ Запуск", callback_data="start"),
        InlineKeyboardButton("⏹ Стоп", callback_data="stop"),
    )
    return markup


def smoke_keyboard():
    markup = InlineKeyboardMarkup()
    markup.row(
        InlineKeyboardButton("🚬 Покурил", callback_data="smoke_done"),
        InlineKeyboardButton("💪 Остался здоровым", callback_data="smoke_healthy"),
    )
    markup.row(
        InlineKeyboardButton("😂 Рандомную шутку", callback_data="smoke_joke")
    )
    return markup


def answer_callback(callback_id: str, text: str):
    try:
        bot.answer_callback_query(callback_id, text)
    except Exception as exc:
        logger.error(f"Ошибка ответа на кнопку: {exc}")


START_SCHEDULE_TEXT = (
    "▶️ Напоминания включены.\n"
    "\n"
    "Каждый день:\n"
    "• 00:00 → 🌙 Спокойной ночи\n"
    "• 06:00 → ☀️ Доброе утра ёпта\n"
    "• 18:55 → за Сонькой ❤️\n"
    "\n"
    "По будням:\n"
    "• 08:55, 09:55, 10:55, 12:55, 13:55, 14:55 → 🔥 ПЕРЕКУР\n"
    "• 11:30 → 🍽 ОБЕД\n"
    "• Пн–Чт: 15:55 → 🔥 ПЕРЕКУР, 16:55 → до завтра!\n"
    "• Пт: 15:55 → 🔥 ПЕРЕКУР и до завтра!\n"
    "\n"
    "💬 Агент Смит подключён к матрице.\n"
    "Если матрица недоступна — бот уточняет простыми вопросами."
)

STOP_TEXT = "⏹ Бот остановлен.\nНажми «Запуск», чтобы возобновить."


# ═══════════════════════════════════════
# КОМАНДЫ И КНОПКИ
# ═══════════════════════════════════════

@bot.message_handler(commands=["start"])
def cmd_start(msg):
    global user_chat_id

    user_chat_id = msg.chat.id
    name = msg.from_user.first_name if msg.from_user else "странник"
    username = msg.from_user.username if msg.from_user else "unknown"

    logger.info(f"/start от {username} (ID: {user_chat_id})")

    bot.send_message(
        user_chat_id,
        f"Симуляция активирована, {name}. 👋\n"
        "Нажми «Запуск», чтобы включить напоминания.",
        reply_markup=keyboard(),
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
        "🚬 Покурил / 💪 Остался здоровым / 😂 Рандомную шутку\n"
        "\n"
        "Пиши боту что угодно — отвечает Агент Смит.\n"
        "Если матрица отключена — уточняющие вопросы.",
    )


def edit_callback_message(call, text: str):
    if not call.message:
        return

    try:
        bot.edit_message_text(
            text,
            chat_id=call.message.chat.id,
            message_id=call.message.message_id,
            reply_markup=keyboard(),
        )
    except Exception as exc:
        logger.error(f"Не удалось отредактировать сообщение: {exc}")


@bot.callback_query_handler(func=lambda call: True)
def on_callback(call):
    global bot_running, user_chat_id

    if call.data == "smoke_done":
        answer_callback(call.id, "🚬 Записал")
        if call.message:
            send_one(
                call.message.chat.id,
                random.choice(SMOKE_DONE_SARCASM),
                "сарказм: покурил",
            )
        logger.info("Перекур: 'Покурил'")
        return

    if call.data == "smoke_healthy":
        answer_callback(call.id, "💪 Записал")
        if call.message:
            send_one(
                call.message.chat.id,
                random.choice(SMOKE_HEALTHY_SARCASM),
                "сарказм: остался здоровым",
            )
        logger.info("Перекур: 'Остался здоровым'")
        return

    if call.data == "smoke_joke":
        answer_callback(call.id, "Лови шутку")
        if call.message:
            send_one(call.message.chat.id, get_joke(), "рандомный каламбур")
        logger.info("Перекур: запрос шутки")
        return

    if not call.message:
        return

    if call.data == "start":
        if bot_running:
            answer_callback(call.id, "Уже работает")
            return

        bot_running = True
        user_chat_id = call.message.chat.id

        answer_callback(call.id, "Запущено")
        edit_callback_message(call, START_SCHEDULE_TEXT)
        logger.info("Бот запущен кнопкой")

    elif call.data == "stop":
        if not bot_running:
            answer_callback(call.id, "Уже остановлен")
            return

        bot_running = False

        answer_callback(call.id, "Остановлено")
        edit_callback_message(call, STOP_TEXT)
        logger.info("Бот остановлен кнопкой")

    else:
        answer_callback(call.id, "Неизвестная кнопка")


# ═══════════════════════════════════════
# РАЗГОВОРНЫЙ РЕЖИМ (СМИТ + FALLBACK)
# ═══════════════════════════════════════

@bot.message_handler(
    func=lambda msg: not (msg.text and msg.text.startswith("/")),
    content_types=[
        "text",
        "photo",
        "sticker",
        "voice",
        "video",
        "document",
        "audio",
        "video_note",
        "animation",
        "location",
        "contact",
    ],
)
def handle_any_message(msg):
    now = time.time()

    if now - last_answer_time.get(msg.chat.id, 0) < ANSWER_COOLDOWN:
        return

    last_answer_time[msg.chat.id] = now

    if msg.content_type == "text" and msg.text:
        user_text = msg.text.strip()
    else:
        user_text = f"[{msg.content_type}]"

    if not user_text:
        return

    # Показываем typing 4 секунды, пока Смит думает
    send_typing(msg.chat.id, seconds=4)

    # Пробуем LLM
    smith_answer = ask_llm(msg.chat.id, user_text)

    if smith_answer:
        send_one(msg.chat.id, smith_answer, f"Смит: {user_text[:40]}")
    else:
        # Fallback: простой уточняющий вопрос
        question = random.choice(CLARIFY_QUESTIONS)
        send_one(msg.chat.id, question, f"fallback на {msg.content_type}")


# ═══════════════════════════════════════
# ПЛАНИРОВЩИК
# ═══════════════════════════════════════

def scheduler():
    global sent_today, last_date

    while True:
        time.sleep(CHECK_INTERVAL)

        if not bot_running or user_chat_id is None:
            continue

        now = datetime.now()

        if now.date() != last_date:
            sent_today.clear()
            last_date = now.date()

        for h, m, text in get_today_schedule(now):
            key = (h, m)

            if key in sent_today:
                continue

            event_time = now.replace(hour=h, minute=m, second=0, microsecond=0)
            age_minutes = (now - event_time).total_seconds() / 60.0

            if age_minutes < 0:
                break

            if age_minutes > CATCH_UP_MINUTES:
                sent_today.add(key)
                continue

            reply_markup = smoke_keyboard() if is_smoke_event(text) else None

            if send_one(
                user_chat_id,
                text,
                f"событие {h:02d}:{m:02d} {text}",
                reply_markup=reply_markup,
            ):
                sent_today.add(key)
                time.sleep(0.4)


def run_bot():
    while True:
        try:
            logger.info("Смит подключился к матрице. Жду /start ...")
            bot.polling(none_stop=True, interval=1, timeout=20)
        except Exception as exc:
            logger.error(f"Polling упал: {exc}")
            logger.info("Переподключаюсь через 10 секунд...")
            time.sleep(10)


if __name__ == "__main__":
    threading.Thread(target=scheduler, daemon=True).start()
    run_bot()
PYEOF

python ~/perkur_bot.py