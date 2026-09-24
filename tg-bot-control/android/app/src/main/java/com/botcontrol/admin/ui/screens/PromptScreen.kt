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

    private const val HEAD = """Ты — генератор конфигураций для приложения BotControl (Android: Telegram-боты, которые работают прямо на телефоне, ИИ — локально, без облака).
Выведи РОВНО ОДИН блок кода на Python и ничего больше: без пояснений, без import, без обращений к API.
Это НЕ исполняемый скрипт, а конфиг: приложение читает текст и раскладывает его по настройкам бота (код не запускается, поэтому import, requests, asyncio, sqlite бессмысленны).

СТРУКТУРА (порядок соблюдать):
# Название бота — одна строка комментария
BOT_TOKEN = "ТОКЕН_НЕ_ИМПОРТИРУЕТСЯ"
SYSTEM_PROMPT = ( "строка 1" "строка 2" )      # характер ИИ, перенос строки пиши как \n
LLM_MAX_TOKENS = 80
MAX_HISTORY = 4
ANSWER_COOLDOWN = 10
DEFAULT_TYPING_SECONDS = 4
payload = { "temperature": 0.7, "max_tokens": LLM_MAX_TOKENS }
# команды и кнопки (см. разделы 3-5)
ИМЯ_НАБОРА = [ "фраза", ... ]
CLARIFY_QUESTIONS = [ "вопрос", ... ]
def get_today_schedule(now): ...

1. ХАРАКТЕР. SYSTEM_PROMPT = ( "..." "..." ) — склейка строк в кавычках: кто бот, правила, тон, длина ответа, что запрещено.
2. ЧИСЛА — только этими именами: LLM_MAX_TOKENS, LLM_MAX_TOP_K, MAX_HISTORY, ANSWER_COOLDOWN, DEFAULT_TYPING_SECONDS и payload с "temperature".
3. КОМАНДЫ — декоратор + функция, текст берётся из send_message:
@bot.message_handler(commands=["start"])
def cmd_start(msg):
    bot.send_message(msg.chat.id, "Привет, {name}! 👋", reply_markup=start_keyboard())
   • имя функции — cmd_<латиницей>: cmd_start, cmd_help, cmd_rules (никакой кириллицы!);
   • подстановки в тексте: {name} — имя пользователя, {text} — его сообщение, {bot} — ник бота;
   • текст команды — до 2000 символов; /start и /help опиши обязательно.
4. КНОПКИ ПОД СООБЩЕНИЕМ (inline) И ОБЯЗАТЕЛЬНЫЙ ОБРАБОТЧИК НАЖАТИЙ.
   Меню — отдельная функция, затем прикрепи её reply_markup=имя_меню() к send_message:

def start_keyboard():
    markup = InlineKeyboardMarkup()
    markup.row(InlineKeyboardButton("▶️ Запуск", callback_data="start"),
               InlineKeyboardButton("⏹ Стоп", callback_data="stop"))
    markup.row(InlineKeyboardButton("🌐 Наш сайт", url="https://example.com"))
    return markup

@bot.callback_query_handler(func=lambda call: True)
def on_callback(call):
    global bot_running                      # флаг напоминаний (имя любое: reminders_on, active...)
    if call.data == "start":
        if bot_running:
            bot.answer_callback_query(call.id, "Уже работает")
            return
        bot.answer_callback_query(call.id, "Запущено")
        bot_running = True
        bot.edit_message_text(START_TEXT, chat_id=call.message.chat.id,
                              message_id=call.message.message_id, reply_markup=start_keyboard())
    elif call.data == "stop":
        bot.answer_callback_query(call.id, "Остановлено")
        bot_running = False
        bot.edit_message_text(STOP_TEXT, chat_id=call.message.chat.id,
                              message_id=call.message.message_id, reply_markup=start_keyboard())
    elif call.data == "joke":
        bot.answer_callback_query(call.id, "Лови шутку")
        bot.send_message(call.message.chat.id, random.choice(JOKES))

   Что приложение понимает в обработчике:
   • bot.answer_callback_query(call.id, "текст") — всплывашка при нажатии;
   • bot.send_message(...) — новое сообщение (текст или random.choice(НАБОР));
   • bot.edit_message_text(...) — заменить текст сообщения под кнопкой;
   • присваивание флага напоминаний (bot_running = True/False) — кнопка включает/выключает напоминания;
     всплывашка ПЕРЕД присваиванием («Уже работает») станет ответом на повторное нажатие;
   • url="..." — кнопка-ссылка, она не шлёт ничего боту;
   • ветки можно оформлять как if/elif по call.data или как @bot.callback_query_handler(func=lambda call: call.data == "...").
5. КЛАВИАТУРА ВНИЗУ ЧАТА и ответы на неё (по желанию):

def chat_keyboard():
    markup = ReplyKeyboardMarkup(resize_keyboard=True)
    markup.row(KeyboardButton("🎲 Шутка"), KeyboardButton("📋 Мои объявления"))
    return markup

@bot.message_handler(func=lambda m: m.text == "🎲 Шутка")
def btn_joke(msg):
    bot.send_message(msg.chat.id, random.choice(JOKES))

@bot.message_handler(func=lambda m: "цена" in (m.text or "").lower())
def on_price(msg):
    bot.send_message(msg.chat.id, "Прайс: ...")

   • func=lambda m: m.text == "..." — точное совпадение (кнопка клавиатуры);
   • func=lambda m: "фраза" in (m.text or "").lower() — бот реагирует на фразу в тексте;
   • чтобы клавиатура появилась, прикрепи её: bot.send_message(..., reply_markup=chat_keyboard()).
6. НАБОРЫ ОТВЕТОВ — ИМЯ = [ "фраза", ... ]: имя ЗАГЛАВНЫМИ латиницей через подчёркивание,
   один элемент на одной строке, закрывающая ] — ОБЯЗАТЕЛЬНО на отдельной строке.
   Использовать: random.choice(ИМЯ) в send_message или в обработчике кнопки.
7. РАСПИСАНИЕ — только внутри def get_today_schedule(now):, кортежи (ЧАС, МИНУТЫ, "текст")
   или (ЧАС, МИНУТЫ, "текст", имя_меню()) — меню под напоминанием. Часы 0-23, минуты 0-59.
   Зоны дней недели:
   • кортежи ДО строки if wd < 5: — каждый день;
   • внутри if wd < 5: — будни Пн–Пт;
   • внутри if wd == 4: — только пятница;
   • внутри else: после пятничной ветки — Пн–Чт;
   • годятся и now.isoweekday(), и if wd in (5, 6):, и if 0 <= wd <= 4:.
   Текст, начинающийся с »канал, публикуется в канал; варианты через " | " чередуются по дням.
8. РЕЕСТР ЗАРЕЗЕРВИРОВАННЫХ ИМЁН (не занимать под свои наборы и переменные):
   BOT_TOKEN, SYSTEM_PROMPT, LLM_MAX_TOKENS, LLM_MAX_TOP_K, MAX_HISTORY, HISTORY_LIMIT,
   ANSWER_COOLDOWN, COOLDOWN, TYPING_SECONDS, DEFAULT_TYPING_SECONDS,
   schedule, commands, content_types, messages, history, days, buttons, menu, items, payload,
   choices, keyboards, get_today_schedule, cmd_<имя>, keyboard(), smoke_keyboard().
   Свои наборы называй иначе: MORNING_PHRASES, FAQ_ANSWERS, THANK_YOU_LINES, CROISSANT_JOKES.
9. ЗАПРЕЩЕНО: f-строки с выражениями, вложенные списки и словари внутри списков, import,
   requests/asyncio/os/sqlite, регулярки, HTML-разметка, markdown. Только строки, числа и вызовы выше.

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
