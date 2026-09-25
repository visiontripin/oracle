package com.botcontrol.admin.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Промт для создания бота: готовый текст, который нужно отправить любой
 * нейросети (или ввести в чат с ИИ в этом же приложении), чтобы получить
 * конфиг бота в формате импорта. Здесь же — кнопка «Копировать».
 *
 * Встроенных примеров ботов в приложении больше нет: примеры создаются
 * этим промтом под конкретную задачу.
 */
object BotPrompt {

    private const val HEAD = """Ты — генератор конфигураций для BotControl (Android: локальные Telegram-боты, ИИ — на устройстве).
Выведи РОВНО ОДИН блок Python-кода и ничего больше: без пояснений, без import. Это конфиг, а не исполняемый скрипт — приложение читает текст и раскладывает его по настройкам бота.

СТРУКТУРА (порядок):
# Название бота
BOT_TOKEN = "НЕ_ИМПОРТИРУЕТСЯ"
SYSTEM_PROMPT = ( "строка 1" "строка 2" )      # ТОЛЬКО если нужен сценарий ИИ; перенос строки как \n
LLM_MAX_TOKENS = 80
MAX_HISTORY = 4
ANSWER_COOLDOWN = 10
DEFAULT_TYPING_SECONDS = 4
payload = { "temperature": 0.7 }
ИМЯ_НАБОРА = [ "фраза", ... ]
CLARIFY_QUESTIONS = [ "вопрос", ... ]
def get_today_schedule(now): ...
команды, клавиатуры, обработчики кнопок (разделы 3-7)

ПРАВИЛА:
1. ВСЁ, что бот ДЕЛАЕТ (команды, кнопки, наборы, расписание) — КОДОМ. ИИ не отвечает «на всё»: SYSTEM_PROMPT используется только в отдельных сценариях — команда /chat и правила с действием «ИИ» (пользователь включает их сам после импорта). Характер — кратко: роль, тон, длина ответа, запреты.
2. ЧИСЛА — только эти имена: LLM_MAX_TOKENS, LLM_MAX_TOP_K, MAX_HISTORY, ANSWER_COOLDOWN, DEFAULT_TYPING_SECONDS и payload с "temperature".
3. КОМАНДЫ — декоратор + функция, текст берётся из send_message:
@bot.message_handler(commands=["start"])
def cmd_start(msg):
    bot.send_message(msg.chat.id, "Привет, {name}! 👋", reply_markup=start_keyboard())
   • имя функции — cmd_<латиницей>; подстановки: {name} — имя пользователя, {text} — его сообщение, {bot} — ник бота;
   • текст до 2000 символов; /start и /help — ОБЯЗАТЕЛЬНО.
4. КНОПКИ (inline) — у КАЖДОЙ кнопки СВОЙ callback_data и СВОЯ ВЕТКА в обработчике нажатий:
def start_keyboard():
    markup = InlineKeyboardMarkup()
    markup.row(InlineKeyboardButton("▶️ Запуск", callback_data="start"),
               InlineKeyboardButton("⏹ Стоп", callback_data="stop"))
    markup.row(InlineKeyboardButton("🌐 Сайт", url="https://example.com"))
    return markup

@bot.callback_query_handler(func=lambda call: True)
def on_callback(call):
    global reminders_on                    # общий флаг (имя любое: active, bot_running...)
    if call.data == "start":
        if reminders_on:
            bot.answer_callback_query(call.id, "Уже работает")
            return
        bot.answer_callback_query(call.id, "Запущено")
        reminders_on = True
        bot.edit_message_text(TEXT_ON, chat_id=call.message.chat.id,
                              message_id=call.message.message_id, reply_markup=start_keyboard())
    elif call.data == "stop":
        bot.answer_callback_query(call.id, "Остановлено")
        reminders_on = False
        bot.edit_message_text(TEXT_OFF, chat_id=call.message.chat.id,
                              message_id=call.message.message_id, reply_markup=start_keyboard())
    elif call.data == "joke":
        bot.answer_callback_query(call.id, "Лови")
        bot.send_message(call.message.chat.id, random.choice(JOKES))
   Понимается: answer_callback_query → всплывашка; send_message → новое сообщение (текст или random.choice(НАБОР)); edit_message_text → заменить текст под кнопкой; присваивание общего флага (reminders_on = True/False) → вкл/выкл напоминаний; всплывашка ПЕРЕД присваиванием → ответ на повторное нажатие; url="..." → кнопка-ссылка (без callback_data); меню крепится reply_markup=имя_меню().
   callback_data — короткие УНИКАЛЬНЫЕ латинские имена (start, stop, done...).
5. КЛАВИАТУРА ВНИЗУ ЧАТА (необязательно):
def chat_keyboard():
    markup = ReplyKeyboardMarkup(resize_keyboard=True)
    markup.row(KeyboardButton("🎲 Шутка"))
    return markup
@bot.message_handler(func=lambda m: m.text == "🎲 Шутка")
def btn_joke(msg):
    bot.send_message(msg.chat.id, random.choice(JOKES))
   • m.text == "..." — точное совпадение (кнопка клавиатуры);
   • "фраза" in (m.text or "").lower() — реакция на фразу в тексте;
   • клавиатура появляется, только если прикреплена: bot.send_message(..., reply_markup=chat_keyboard()).
6. НАБОРЫ — ИМЯ = [ "фраза", ... ]: имя ЗАГЛАВНЫМИ латиницей с _, одна фраза на строке, закрывающая ] — на отдельной строке. Использование: random.choice(ИМЯ).
7. РАСПИСАНИЕ — только внутри def get_today_schedule(now):, кортежи (ЧАС, МИНУТЫ, "текст") или (ЧАС, МИНУТЫ, "текст", имя_меню()) — меню под напоминанием. Часы 0-23, минуты 0-59. Дни:
   • кортежи ДО if wd < 5: — каждый день; внутри if wd < 5: — будни; внутри if wd == 4: — пятница; внутри else — Пн–Чт;
   • годятся now.isoweekday(), if wd in (5, 6):, if 0 <= wd <= 4:.
   • текст, начинающийся с »канал, — публикация в канал; варианты через " | " чередуются по дням.
8. ЗАРЕЗЕРВИРОВАНО (не занимать): BOT_TOKEN, SYSTEM_PROMPT, LLM_MAX_TOKENS, LLM_MAX_TOP_K, MAX_HISTORY, ANSWER_COOLDOWN, DEFAULT_TYPING_SECONDS, schedule, commands, content_types, messages, history, days, buttons, menu, items, payload, choices, keyboards, get_today_schedule, cmd_*.
   Свои наборы называй иначе: MORNING_PHRASES, FAQ_ANSWERS, THANK_YOU_LINES.
9. ЗАПРЕЩЕНО: f-строки с выражениями, import, requests/asyncio/os, регулярки, вложенные списки/словари, HTML/markdown.
10. ФИШКИ — АНИМАЦИЯ правкой ОДНОГО сообщения (заставки, прогресс-бар, спиннер, флипбук, текстовый квест). Объяви помощника ровно так и вызывай его:
def play_animation(chat_id, frames, delay=0.7, final=None, reply_markup=None, message_id=None, **kw):
    m_id = message_id or bot.send_message(chat_id, frames[0]).message_id
    for frame in frames[1:]:
        time.sleep(delay)
        bot.edit_message_text(frame, chat_id, m_id)
    if final:
        bot.edit_message_text(final, chat_id, m_id, reply_markup=reply_markup)
ANIM_ROCKET = [ "  🚀\n\n🌍", "\n  🚀\n🌍", "\n\n🌍🔥" ]      # свои кадры: имя ANIM_* , 2–60 кадров
   • свои кадры: play_animation(msg.chat.id, ANIM_ROCKET, 0.5, "🛰 На орбите!", mono=True) — mono=True для ASCII-арта;
   • готовый эффект строкой: play_animation(msg.chat.id, "progress", 0.6, "✅ Готово", text="Загрузка"). Эффекты: spinner, progress, dots, countdown, typewriter, matrix, slot, moon, clock, heart;
   • итог из набора: play_animation(chat_id, "slot", 0.5, pack=PREDICTIONS); повторы: loops=2;
   • в обработчике кнопки: message_id=call.message.message_id — анимируется само сообщение с кнопками;
   • delay — от 0.5 до 3 секунд (Telegram не любит частые правки); итог и кнопки — в final и reply_markup.
11. КУБИКИ Telegram (анимированный случайный результат): bot.send_dice(msg.chat.id, emoji="🎲") — годятся 🎲 🎯 🏀 ⚽ 🎳 🎰; можно и в ветке кнопки.

ЗАДАНИЕ: создай бота:"""

    private const val TASK_HINT = """[опиши одним абзацем: роль и характер; что бот делает;
команды кроме /start и /help; наборы ответов (имя — сколько фраз);
расписание (во сколько и в какие дни); кнопки под сообщениями и что они делают;
особые правила: тон, длина ответа, запреты]"""

    /** Полный текст промта: с готовым заданием или с шаблоном для заполнения. */
    fun build(task: String): String {
        val t = task.trim()
        return if (t.isBlank()) "$HEAD\n$TASK_HINT" else "$HEAD\n$t"
    }
}

@Composable
fun PromptScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var task by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    val prompt = BotPrompt.build(task)

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text("Промт для бота", style = MaterialTheme.typography.titleLarge)
        }
        Text("Отправь этот текст любой нейросети (ChatGPT, Claude, Gemini — или местному ИИ в «Чат с ИИ»). "
            + "Она вернёт конфиг бота. Сохрани его в .py и импортируй: раздел «Скрипты → Импорт настроек из кода» "
            + "→ «Разобрать» → «Применить к боту».",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.padding(6.dp))

        OutlinedTextField(
            task, { task = it; copied = false },
            label = { Text("Что за бот (необязательно)") },
            placeholder = { Text("Бот кофейни: дружелюбный, коротко; наборы CROISSANT_JOKES 15 фраз…") },
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().height(90.dp),
        )
        Text("Если поле заполнено — в конец промта подставится готовое ЗАДАНИЕ. "
            + "Если оставить пустым — останется шаблон в квадратных скобках.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.padding(6.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = {
                clipboard.setText(AnnotatedString(prompt))
                copied = true
            }, modifier = Modifier.weight(1f)) {
                Text(if (copied) "✅ Скопировано" else "📋 Копировать")
            }
            OutlinedButton(onClick = {
                runCatching {
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "Промт: конфиг бота BotControl")
                        .putExtra(Intent.EXTRA_TEXT, prompt)
                    context.startActivity(Intent.createChooser(send, "Отправить промт"))
                }
            }, modifier = Modifier.weight(1f)) { Text("📤 Поделиться") }
        }
        Spacer(Modifier.padding(6.dp))

        Card(Modifier.fillMaxWidth().weight(1f)) {
            SelectionContainer {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(10.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        prompt,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = MaterialTheme.typography.bodySmall.fontSize,
                            color = MaterialTheme.colorScheme.onBackground,
                        ),
                    )
                    Spacer(Modifier.padding(20.dp))
                }
            }
        }
    }
}
