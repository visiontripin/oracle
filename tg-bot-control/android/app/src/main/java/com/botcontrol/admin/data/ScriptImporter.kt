package com.botcontrol.admin.data

import com.botcontrol.admin.data.PySource.PyExpr

/**
 * Импорт настроек бота из исходника (Python: telebot / aiogram — и конфиги
 * формата BotControl). Код не исполняется: [PySource] разбирает структуру,
 * а здесь она раскладывается по настройкам приложения:
 *
 *  • характер и параметры ИИ, память, паузы, уточняющие вопросы;
 *  • команды (`@bot.message_handler(commands=[…])`) → правила «Команда»;
 *  • ответы на текст (`func=lambda m: m.text == "…"` / `"…" in m.text`) →
 *    правила «Кнопка» / «Содержит»;
 *  • inline-меню (функции с InlineKeyboardMarkup, ряды `markup.row(…)`,
 *    `callback_data` и `url`) → кнопки под сообщениями правил и событий;
 *  • обработчики нажатий (`callback_query_handler`, ветки `call.data == "…"`)
 *    → действие кнопки: текст, случайное из набора, вкл/выкл напоминаний
 *    (`bot_running = True/False`), правка сообщения, всплывашка;
 *  • клавиатура чата (ReplyKeyboardMarkup / KeyboardButton);
 *  • анимации правкой сообщения: помощник `play_animation(chat, КАДРЫ, 0.5,
 *    "итог")` или цикл `for кадр in КАДРЫ: … edit_message_text(кадр …)` с
 *    `time.sleep(N)` → действие «Анимация»; `send_dice(…, emoji="🎯")` → «Кубик»;
 *  • наборы ответов (списки строк) и расписание с зонами дней недели
 *    (`if wd < 5:` / `if wd == 4:` / `else:` / `if wd in (5, 6):` …),
 *    4-й элемент кортежа события — функция меню под напоминанием.
 *
 * Никаких встроенных «примеров»: что есть в коде — то и импортируется.
 */
object ScriptImporter {

    /** Правило ответа из кода. packId — «imp_<КОНСТАНТА>» (при применении
     *  переименовывается под конкретного бота). */
    data class ImportedRule(
        val type: String,          // command | button | contains
        val pattern: String,       // "/start" | надпись кнопки | фраза
        val actionType: String,    // text | pack | llm | script | anim | dice
        val text: String = "",
        val packId: String = "",
        val script: String = "",
        val menu: List<InlineBtn> = emptyList(),
    )

    /** Блок сводки для экрана импорта: ключ категории, заголовок, детали. */
    data class Section(val key: String, val title: String, val details: List<String>)

    data class Parsed(
        val systemPrompt: String? = null,
        val temperature: Float? = null,
        val maxTokens: Int? = null,
        val topK: Int? = null,
        val historyLimit: Int? = null,
        val cooldownSec: Int? = null,
        val typingSec: Int? = null,
        val clarify: List<String> = emptyList(),
        val packs: List<ReplyPack> = emptyList(),
        val schedule: List<ScheduleEvent> = emptyList(),
        val rules: List<ImportedRule> = emptyList(),
        val menuCommands: List<MenuCommand> = emptyList(),
        val keyboardRows: List<List<String>> = emptyList(),
        val channel: String? = null,
        val listingsOn: Boolean? = null,
        val warnings: List<String> = emptyList(),
    ) {
        val commands: List<ImportedRule> get() = rules.filter { it.type == "command" }
        val textRules: List<ImportedRule> get() = rules.filter { it.type != "command" }

        /** Все inline-кнопки: правила + события. */
        val allButtons: List<InlineBtn>
            get() = rules.flatMap { it.menu } + schedule.flatMap { it.menu }

        fun isEmpty(): Boolean = systemPrompt == null && temperature == null &&
            maxTokens == null && topK == null && historyLimit == null &&
            cooldownSec == null && typingSec == null && clarify.isEmpty() &&
            packs.isEmpty() && schedule.isEmpty() && rules.isEmpty() &&
            menuCommands.isEmpty() && keyboardRows.isEmpty() && channel == null &&
            listingsOn == null

        fun sections(): List<Section> = buildList {
            val ai = buildList {
                systemPrompt?.let { add("Характер ИИ (${it.length} симв.)") }
                temperature?.let { add("Температура: $it") }
                maxTokens?.let { add("Максимум токенов: $it") }
                topK?.let { add("Top-K: $it") }
            }
            if (ai.isNotEmpty()) add(Section(SEC_AI, "🤖 ИИ", ai))
            val beh = buildList {
                historyLimit?.let { add("Память диалога: $it реплик") }
                cooldownSec?.let { add("Пауза между ответами: $it с") }
                typingSec?.let { add("«Печатает…»: $it с") }
                if (clarify.isNotEmpty()) add("Уточняющих вопросов: ${clarify.size}")
            }
            if (beh.isNotEmpty()) add(Section(SEC_BEHAVIOR, "⏱ Поведение", beh))
            if (commands.isNotEmpty() || menuCommands.isNotEmpty()) {
                add(Section(SEC_COMMANDS, "💬 Команды", buildList {
                    commands.forEach { add(describe(it)) }
                    if (menuCommands.isNotEmpty())
                        add("Меню Telegram: " + menuCommands.joinToString(", ") { "/" + it.command })
                }))
            }
            if (textRules.isNotEmpty()) {
                add(Section(SEC_RULES, "🔤 Ответы на кнопки клавиатуры и фразы",
                    textRules.map { describe(it) }))
            }
            if (keyboardRows.isNotEmpty()) {
                add(Section(SEC_KEYBOARD, "⌨️ Клавиатура чата (${keyboardRows.sumOf { it.size }} кн.)",
                    keyboardRows.map { row -> row.joinToString("  |  ") }))
            }
            if (packs.isNotEmpty()) {
                add(Section(SEC_PACKS, "🎲 Наборы ответов",
                    packs.map { "«${it.name}»: ${it.items.size} ответов" }))
            }
            if (schedule.isNotEmpty()) {
                val withMenu = schedule.count { it.menu.isNotEmpty() }
                val byDays = schedule.groupBy { it.daysLabel() }
                    .map { (d, list) -> "$d: ${list.size}" }
                add(Section(SEC_SCHEDULE, "⏰ Расписание (${schedule.size} событий)", buildList {
                    add(byDays.joinToString(", "))
                    if (withMenu > 0) add("С кнопками под напоминанием: $withMenu")
                    if (schedule.any { it.toChannel }) add("В канал: ${schedule.count { it.toChannel }}")
                }))
            }
            if (channel != null || listingsOn != null) {
                add(Section(SEC_CHANNEL, "📢 Канал и объявления", buildList {
                    channel?.let { add("Канал: $it") }
                    listingsOn?.let { add(if (it) "Режим объявлений: вкл" else "Режим объявлений: выкл") }
                }))
            }
        }

        private fun describe(r: ImportedRule): String {
            val head = when (r.type) {
                "command" -> r.pattern
                "button" -> "Кнопка «${r.pattern}»"
                else -> "Содержит «${r.pattern}»"
            }
            val act = when (r.actionType) {
                "pack" -> "случайное из ${r.packId.removePrefix("imp_")}"
                "llm" -> "ответ ИИ"
                "script" -> "скрипт JS"
                "anim" -> "анимация: " + Anim.describe(Anim.decode(r.script))
                "dice" -> "кубик ${r.text}"
                else -> "текст (${r.text.length} симв.)"
            }
            val btns = if (r.menu.isNotEmpty())
                " + кнопки: " + r.menu.joinToString(", ") { it.label } else ""
            return "$head → $act$btns"
        }
    }

    const val SEC_AI = "ai"
    const val SEC_BEHAVIOR = "behavior"
    const val SEC_COMMANDS = "commands"
    const val SEC_RULES = "rules"
    const val SEC_KEYBOARD = "keyboard"
    const val SEC_PACKS = "packs"
    const val SEC_SCHEDULE = "schedule"
    const val SEC_CHANNEL = "channel"

    /** Главная точка входа: текст исходника → найденные настройки. */
    fun parse(source: String): Parsed = Parser(decodeEntities(source)).run()

    /**
     * Код, скопированный из браузера/мессенджера, часто приходит с
     * HTML-сущностями: `if wd &lt; 5:` вместо `if wd < 5:` — и зоны дней
     * недели не распознавались (ПЕРЕКУРы становились ежедневными).
     */
    fun decodeEntities(source: String): String {
        if (!source.contains('&')) return source
        return source.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&#x27;", "'").replace("&nbsp;", " ")
            .replace("&amp;", "&")
    }

    /** Человекочитаемое имя набора из имени константы: JOKES → «📦 Jokes». */
    fun humanize(constName: String): String =
        constName.lowercase().split('_')
            .filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            .let { "📦 $it" }

    /** Имена, которые почти всегда служебные, а не наборы ответов. */
    val SERVICE_LIST_NAMES = setOf(
        "schedule", "content_types", "commands", "messages", "history",
        "days", "buttons", "menu", "items", "payload", "choices", "keyboards",
        "allowed_updates", "admins", "admin_ids", "users",
    )
}

// ======================================================================
// Разбор
// ======================================================================

private class Parser(source: String) {
    private val src = PySource(source)
    private val t = src.text
    private val warnings = LinkedHashSet<String>()

    private val moduleExprs = src.moduleAssignments()
    private val strConsts = HashMap<String, String>()
    private val listConsts = LinkedHashMap<String, List<String>>()
    private val listTitles = HashMap<String, String>()

    private enum class Kind { SEND, EDIT, TOAST, LLM }
    private data class Helper(val kind: Kind, val textIndex: Int, val markupIndex: Int, val fixedMarkup: String?)

    private data class KbButton(val label: String, val callback: String? = null, val url: String? = null)
    private data class Keyboard(val inline: Boolean, val rows: List<List<KbButton>>)

    private val packHelpers = HashMap<String, String>()
    /** Функции-помощники анимации: цикл + edit_message_text внутри. */
    private val animHelpers = HashSet<String>()
    private val animEditFns = setOf("edit_message_text", "edit_message_media", "edit_message_caption")
    private val helpers = HashMap<String, Helper>()
    private val keyboards = LinkedHashMap<String, Keyboard>()
    private val attachedKeyboards = HashSet<String>()

    private data class Branch(val func: PySource.PyFunc, val lines: List<PySource.LLine>, val inline: IntRange?)
    private val cbBranches = LinkedHashMap<String, Branch>()

    private data class Action(
        val kind: String = "none",   // text | pack | llm | script | on | off | anim | dice | none
        val text: String = "",
        val pack: String = "",
        val script: String = "",
        val edit: Boolean = false,
        val toast: String = "",
        val toastNoChange: String = "",
        val markup: MarkupRef? = null,
        val anim: AnimSpec? = null,
    )

    /** Ссылка на клавиатуру из reply_markup: функция или локальная разметка. */
    private data class MarkupRef(val fn: String?, val kb: Keyboard)

    private data class TextVal(val kind: String, val text: String = "", val pack: String = "", val script: String = "")

    fun run(): ScriptImporter.Parsed {
        collectConstants()
        classifyHelpers()
        collectKeyboards()
        collectCallbackBranches()

        val rules = ArrayList<ScriptImporter.ImportedRule>()
        var chatKb: MarkupRef? = null
        var chatKbFromStart = false
        val seenCommands = HashSet<String>()

        // ---------- обработчики сообщений ----------
        for (f in src.functions) {
            if (f.decorators.isEmpty()) continue
            for (deco in f.decoratorRanges) {
                val d = decoratorInfo(deco) ?: continue
                if (d.callback) continue
                if (d.commands.isEmpty() && d.buttons.isEmpty() && d.contains.isEmpty()) continue
                val act = analyze(f, f.body, f.inlineBody)
                val (menu, replyKb) = splitMarkup(act.markup, f.name)
                if (replyKb != null) {
                    val isStart = d.commands.any { it == "start" }
                    if (chatKb == null || (isStart && !chatKbFromStart)) {
                        chatKb = replyKb; chatKbFromStart = isStart
                    }
                }
                val base = ruleFromAction(act, menu)
                d.commands.forEach { cmd ->
                    if (seenCommands.add(cmd)) rules.add(base.copy(type = "command", pattern = "/$cmd"))
                }
                d.buttons.forEach { label -> rules.add(base.copy(type = "button", pattern = label)) }
                d.contains.forEach { word -> rules.add(base.copy(type = "contains", pattern = word)) }
            }
        }
        // старая грамматика: def cmd_xxx без декоратора
        for (f in src.functions) {
            if (f.decorators.isNotEmpty() || f.header.indent != 0) continue
            val m = Regex("^cmd_(\\w+)$").find(f.name) ?: continue
            val cmd = m.groupValues[1].lowercase()
            if (!seenCommands.add(cmd)) continue
            val act = analyze(f, f.body, f.inlineBody)
            val (menu, _) = splitMarkup(act.markup, f.name)
            rules.add(ruleFromAction(act, menu).copy(type = "command", pattern = "/$cmd"))
        }
        // JS / прочее: только имена команд
        val jsCommands = LinkedHashSet<String>()
        Regex("""onText\(\s*/\\?/(\w+)""").findAll(t).forEach { jsCommands.add(it.groupValues[1].lowercase()) }
        jsCommands.filter { seenCommands.add(it) }.forEach {
            rules.add(ScriptImporter.ImportedRule("command", "/$it", "text", text = ""))
            warnings.add("Команда /$it найдена в JS-коде — текст ответа задай в правилах")
        }

        // Клавиатура чата: прикреплённая к /start, иначе единственная объявленная.
        if (chatKb == null) {
            val replyFns = keyboards.filter { !it.value.inline }
            if (replyFns.size == 1) chatKb = MarkupRef(replyFns.keys.first(), replyFns.values.first())
        }
        chatKb?.fn?.let { attachedKeyboards.add(it) }

        val schedule = scheduleEvents()

        // Неиспользованные inline-меню — подсказка, куда их прикрепить.
        keyboards.filter { it.value.inline && it.key !in attachedKeyboards }.keys.forEach {
            warnings.add("Меню $it() объявлено, но не прикреплено: добавь reply_markup=$it() в команду " +
                "или 4-м элементом события расписания (ЧАС, МИН, \"текст\", $it())")
        }

        val referencedPacks = HashSet<String>()
        (rules.map { it.packId } + (rules.flatMap { it.menu } + schedule.flatMap { it.menu }).map { it.packId })
            .filter { it.startsWith("imp_") }.forEach { referencedPacks.add(it.removePrefix("imp_")) }
        // набор внутри анимации (pack= у play_animation)
        (rules.filter { it.actionType == "anim" }.map { it.script } +
            (rules.flatMap { it.menu } + schedule.flatMap { it.menu }).filter { it.action == "anim" }.map { it.script })
            .map { Anim.decode(it).packId }.filter { it.startsWith("imp_") }
            .forEach { referencedPacks.add(it.removePrefix("imp_")) }

        val clarifyName = listOf("CLARIFY_QUESTIONS", "CLARIFY").firstOrNull { it in listConsts }
            ?: listConsts.keys.firstOrNull { it.startsWith("CLARIFY") }
        val packs = listConsts.filter { (name, items) ->
            name != clarifyName && (name in referencedPacks ||
                (items.size >= 2 && name.lowercase() !in ScriptImporter.SERVICE_LIST_NAMES &&
                    name == name.uppercase() && name !in animFrameNames &&
                    !name.startsWith("ANIM_") && !name.endsWith("_FRAMES")))
        }.map { (name, items) ->
            ReplyPack(id = "imp_$name", name = listTitles[name] ?: ScriptImporter.humanize(name),
                items = items.take(500))
        }
        referencedPacks.filter { it !in listConsts }.forEach {
            warnings.add("Набор $it используется кнопкой/правилом, но список не найден")
        }

        val prompt = (moduleExprs["SYSTEM_PROMPT"] as? PyExpr.Str)?.value?.trim()?.ifBlank { null }
            ?: legacyPrompt()

        return ScriptImporter.Parsed(
            systemPrompt = prompt,
            temperature = firstFloat(listOf("""temperature["'\s:=]+([0-9.]+)""")),
            maxTokens = firstInt(listOf(
                """["']max_tokens["']\s*:\s*(\d+)""", """LLM_MAX_TOKENS\s*=\s*(\d+)""",
                """max_tokens\s*=\s*(\d+)""", """maxTokens\s*[:=]\s*(\d+)""")),
            topK = firstInt(listOf("""top_k["'\s:=]+(\d+)""", """topK\s*[:=]\s*(\d+)""",
                """LLM_MAX_TOP_K\s*=\s*(\d+)""")),
            historyLimit = firstInt(listOf("""MAX_HISTORY\s*=\s*(\d+)""", """HISTORY_LIMIT\s*=\s*(\d+)""")),
            cooldownSec = firstInt(listOf("""ANSWER_COOLDOWN\s*=\s*(\d+)""", """(?<![\w.])COOLDOWN\s*[:=]\s*(\d+)""")),
            typingSec = firstInt(listOf(
                """DEFAULT_TYPING_SECONDS\s*=\s*(\d+)""",
                """install_typing_plugin\([^)]*seconds\s*=\s*(\d+)""",
                """(?<![\w.])TYPING_SECONDS\s*=\s*(\d+)""")),
            clarify = clarifyName?.let { listConsts[it] }.orEmpty(),
            packs = packs,
            schedule = schedule,
            rules = rules,
            menuCommands = menuCommands(rules),
            keyboardRows = chatKb?.kb?.rows?.map { row -> row.map { it.label.take(32) } }
                ?.filter { it.isNotEmpty() }.orEmpty(),
            channel = channelSetting(),
            listingsOn = listOf("LISTINGS_MODE", "LISTINGS_ON", "LISTINGS")
                .firstNotNullOfOrNull { (moduleExprs[it] as? PyExpr.Const)?.value },
            warnings = warnings.toList(),
        )
    }

    // ------------------------------------------------------------------
    // константы и помощники
    // ------------------------------------------------------------------

    private fun collectConstants() {
        for ((name, e) in moduleExprs) {
            when (e) {
                is PyExpr.Str -> strConsts[name] = e.value
                is PyExpr.Seq -> if ((e.open == '[' || e.open == '(') && e.items.isNotEmpty() &&
                    e.items.all { it is PyExpr.Str }) {
                    listConsts[name] = e.items.map { normalizeText((it as PyExpr.Str).value) }
                        .filter { it.isNotBlank() }
                    // «# Название набора» на строке «NAME = [»
                    val title = src.trailingComment(e.start)
                    if (title.isNotBlank()) listTitles[name] = title.take(60)
                }
                else -> {}
            }
        }
    }

    private val llmNameRe = Regex("""(?i)(^|_)(ask_?llm|llm|ask_ai|ai_reply|gpt|openai|ollama|generate_reply|smith_reply|ask_model|chat_completion)""")

    private fun classifyHelpers() {
        for (f in src.functions) {
            if (f.decorators.isNotEmpty()) continue
            val bs = f.bodyStart
            val be = f.bodyEnd
            if (be <= bs) continue
            val body = src.masked.substring(bs, be)
            // def get_joke(): return random.choice(JOKES)
            Regex("""return\s+(?:random\.)?choice\(\s*([A-Za-z_]\w*)\s*\)""").find(body)?.let {
                packHelpers[f.name] = it.groupValues[1]
            }
            if (llmNameRe.containsMatchIn(f.name) || body.contains("chat/completions") ||
                body.contains("ChatCompletion") || body.contains("/api/generate")) {
                helpers[f.name] = Helper(Kind.LLM, -1, -1, null)
                continue
            }
            val inner = src.calls(bs, be)
            // def play_animation(chat_id, frames, delay, final): for f in frames: … edit_message_text
            // (и «for i, frame in enumerate(frames)», и фото: edit_message_media / _caption)
            if (Regex("""(?m)^\s*for\s+\(?\s*\w+(\s*,\s*\w+)*\s*\)?\s+in\s""").containsMatchIn(body) &&
                inner.any { it.shortFn in animEditFns || it.fn.endsWith(".edit_text") }) {
                animHelpers.add(f.name)
                continue
            }
            fun paramIndex(e: PyExpr?): Int =
                (e as? PyExpr.Name)?.let { n -> f.params.indexOf(n.id) } ?: -1
            fun markupOf(c: PyExpr.Call): Pair<Int, String?> {
                val m = c.kwargs["reply_markup"] ?: return -1 to null
                return when (m) {
                    is PyExpr.Name -> paramIndex(m) to null
                    is PyExpr.Call -> -1 to m.fn
                    else -> -1 to null
                }
            }
            inner.firstOrNull { it.shortFn == "edit_message_text" || it.fn.endsWith(".edit_text") }?.let { c ->
                val (mi, fixed) = markupOf(c)
                helpers[f.name] = Helper(Kind.EDIT, paramIndex(c.arg(0, "text")), mi, fixed)
                return@let
            }
            if (f.name in helpers) continue
            inner.firstOrNull { it.shortFn == "answer_callback_query" }?.let { c ->
                helpers[f.name] = Helper(Kind.TOAST, paramIndex(c.arg(1, "text")), -1, null)
            }
            if (f.name in helpers) continue
            inner.firstOrNull { it.shortFn == "send_message" || it.shortFn == "reply_to" }?.let { c ->
                val (mi, fixed) = markupOf(c)
                val ti = paramIndex(c.arg(1, "text"))
                if (ti >= 0) helpers[f.name] = Helper(Kind.SEND, ti, mi, fixed)
            }
        }
    }

    // ------------------------------------------------------------------
    // клавиатуры
    // ------------------------------------------------------------------

    private fun collectKeyboards() {
        for (f in src.functions) {
            val bs = f.bodyStart
            val be = f.bodyEnd
            if (be <= bs) continue
            val body = src.masked.substring(bs, be)
            if (!Regex("""KeyboardMarkup|KeyboardBuilder|KeyboardButton""").containsMatchIn(body)) continue
            if (f.decorators.isNotEmpty()) continue // разметка внутри обработчика — локальная
            parseKeyboard(f, null)?.let { if (it.rows.isNotEmpty()) keyboards[f.name] = it }
        }
    }

    private val markupCtors = setOf("InlineKeyboardMarkup", "ReplyKeyboardMarkup",
        "InlineKeyboardBuilder", "ReplyKeyboardBuilder")

    /** Разметка в теле функции [f]; [varName] — конкретная переменная (или первая). */
    private fun parseKeyboard(f: PySource.PyFunc, varName: String?): Keyboard? {
        val bs = f.bodyStart
        val be = f.bodyEnd
        val calls = src.calls(bs, be)
        val ctor = calls.firstOrNull { c ->
            c.shortFn in markupCtors && (varName == null || assignedName(c) == varName)
        } ?: return null
        val inline = ctor.shortFn.startsWith("Inline")
        val v = assignedName(ctor)
        val rowWidth = ((ctor.kwargs["row_width"] as? PyExpr.Num)?.value?.toInt() ?: 3).coerceIn(1, 8)
        val rows = ArrayList<List<KbButton>>()
        // aiogram: InlineKeyboardMarkup(inline_keyboard=[[…], […]])
        (ctor.kwargs["inline_keyboard"] ?: ctor.kwargs["keyboard"])?.let { e ->
            (e as? PyExpr.Seq)?.items?.forEach { rowE ->
                val row = (rowE as? PyExpr.Seq)?.items?.mapNotNull { button(it, f) }
                    ?: listOfNotNull(button(rowE, f))
                if (row.isNotEmpty()) rows.add(row)
            }
        }
        val pending = ArrayList<KbButton>()
        var adjust: List<Int> = emptyList()
        fun method(name: String, args: List<PyExpr>) {
            when (name) {
                "row" -> args.mapNotNull { button(it, f) }.let { if (it.isNotEmpty()) rows.add(it) }
                "add" -> args.mapNotNull { button(it, f) }.chunked(rowWidth).forEach { rows.add(it) }
                "insert" -> args.mapNotNull { button(it, f) }.forEach { b ->
                    if (rows.isEmpty()) rows.add(listOf(b)) else rows[rows.size - 1] = rows.last() + b
                }
                "adjust" -> adjust = args.mapNotNull { (it as? PyExpr.Num)?.value?.toInt() }
            }
        }
        // цепочка InlineKeyboardMarkup().row(…).row(…)
        chained(ctor.end).forEach { (name, call) -> if (name == "button") builderButton(call, f)?.let { pending.add(it) } else method(name, call.args) }
        if (v != null) {
            for (c in calls) {
                if (!c.fn.startsWith("$v.")) continue
                val name = c.fn.removePrefix("$v.")
                if (name == "button") builderButton(c, f)?.let { pending.add(it) }
                else method(name, c.args)
                chained(c.end).forEach { (n2, c2) -> if (n2 == "button") builderButton(c2, f)?.let { pending.add(it) } else method(n2, c2.args) }
            }
        }
        if (pending.isNotEmpty()) {
            // InlineKeyboardBuilder: .button(…) × N + .adjust(2, 1)
            val sizes = adjust.ifEmpty { listOf(8) }
            var i = 0
            var k = 0
            while (i < pending.size) {
                val size = sizes[minOf(k, sizes.size - 1)].coerceAtLeast(1)
                rows.add(pending.subList(i, minOf(pending.size, i + size)).toList())
                i += size; k++
            }
        }
        return Keyboard(inline, rows)
    }

    private fun builderButton(c: PyExpr.Call, f: PySource.PyFunc): KbButton? {
        val label = strOf(c.arg(0, "text"), f) ?: return null
        return KbButton(label, strOf(c.kwargs["callback_data"], f), strOf(c.kwargs["url"], f))
    }

    /** Методы, вызванные цепочкой сразу после выражения: ").row(…).add(…)". */
    private fun chained(end: Int): List<Pair<String, PyExpr.Call>> {
        val out = ArrayList<Pair<String, PyExpr.Call>>()
        var p = end
        val re = Regex("""^\s*\.\s*([A-Za-z_]\w*)\s*\(""")
        while (p < src.masked.length) {
            val m = re.find(src.masked.substring(p, minOf(src.masked.length, p + 200))) ?: break
            val nameStart = p + m.groups[1]!!.range.first
            val open = p + m.range.last
            val close = src.matchClose(open)
            if (close < 0) break
            val e = src.expr(nameStart, close + 1)
            if (e is PyExpr.Call) out.add(m.groupValues[1] to e)
            p = close + 1
        }
        return out
    }

    /** Имя переменной, которой присвоен вызов [c] (или null). */
    private fun assignedName(c: PyExpr.Call): String? {
        val line = src.lines.lastOrNull { it.start <= c.start && c.start < it.end } ?: return null
        val a = src.assignment(line) ?: return null
        return if (a.second.start == c.start) a.first else null
    }

    private fun button(e: PyExpr, f: PySource.PyFunc?, depth: Int = 0): KbButton? = when (e) {
        is PyExpr.Call -> when (e.shortFn) {
            "InlineKeyboardButton", "KeyboardButton" -> strOf(e.arg(0, "text"), f)?.let { label ->
                KbButton(label, strOf(e.kwargs["callback_data"], f), strOf(e.kwargs["url"], f))
            }
            else -> null
        }
        is PyExpr.Str -> KbButton(normalizeText(e.value))
        is PyExpr.Name -> if (depth > 3) null else
            localExpr(f, e.id, e.start)?.let { button(it, f, depth + 1) }
                ?: strConsts[e.id]?.let { KbButton(it) }
        else -> null
    }

    private fun strOf(e: PyExpr?, f: PySource.PyFunc?): String? = when (e) {
        is PyExpr.Str -> normalizeText(e.value)
        is PyExpr.Name -> strConsts[e.id] ?: localExpr(f, e.id, e.start)?.let { (it as? PyExpr.Str)?.value }
        else -> null
    }

    /** Последнее присваивание локальной переменной [name] до позиции [before]. */
    private fun localExpr(f: PySource.PyFunc?, name: String, before: Int): PyExpr? {
        f ?: return null
        var found: PyExpr? = null
        for (line in f.body) {
            if (line.start >= before) break
            val a = src.assignment(line) ?: continue
            if (a.first == name) found = a.second
        }
        return found
    }

    // ------------------------------------------------------------------
    // декораторы
    // ------------------------------------------------------------------

    private data class Deco(
        val callback: Boolean,
        val commands: List<String> = emptyList(),
        val buttons: List<String> = emptyList(),
        val contains: List<String> = emptyList(),
        val callbackIds: List<String> = emptyList(),
        val dispatcher: Boolean = false,
    )

    private fun decoratorInfo(range: IntRange): Deco? {
        val at = range.first + (src.text.substring(range.first, range.last + 1).indexOf('@')) + 1
        val e = src.expr(at, range.last + 1)
        val call = e as? PyExpr.Call
        val fn = call?.fn ?: (e as? PyExpr.Name)?.id ?: src.maskedOf(e).substringBefore('(')
        val isCallback = fn.contains("callback_query")
        val isMessage = fn.contains("message_handler") || fn.endsWith(".message") ||
            fn.endsWith(".message_handler")
        if (!isCallback && !isMessage) return null
        val region = e.start until e.end
        if (isCallback) {
            val ids = ArrayList<String>()
            var dispatcher = false
            val funcArg = call?.kwargs?.get("func")
            if (funcArg != null) {
                ids.addAll(dataIds(funcArg.start, funcArg.end))
                if (ids.isEmpty()) dispatcher = true
            } else {
                call?.kwargs?.get("text")?.let { strOf(it, null)?.let { s -> ids.add(s) } }
                ids.addAll(dataIds(region.first, region.last + 1))
                if (ids.isEmpty()) dispatcher = true
            }
            return Deco(callback = true, callbackIds = ids, dispatcher = dispatcher)
        }
        // сообщения: команды
        val commands = ArrayList<String>()
        (call?.kwargs?.get("commands") as? PyExpr.Seq)?.items?.forEach { (it as? PyExpr.Str)?.let { s -> commands.add(s.value.lowercase().removePrefix("/")) } }
        (call?.kwargs?.get("commands") as? PyExpr.Str)?.let { commands.add(it.value.lowercase()) }
        src.calls(region.first, region.last + 1).forEach { c ->
            when (c.shortFn) {
                "CommandStart" -> commands.add("start")
                "Command" -> {
                    c.args.forEach { a -> (a as? PyExpr.Str)?.let { commands.add(it.value.lowercase().removePrefix("/")) } }
                    (c.kwargs["commands"] as? PyExpr.Seq)?.items?.forEach { (it as? PyExpr.Str)?.let { s -> commands.add(s.value.lowercase()) } }
                    (c.kwargs["commands"] as? PyExpr.Str)?.let { commands.add(it.value.lowercase()) }
                }
            }
        }
        if (commands.isNotEmpty()) return Deco(callback = false, commands = commands.distinct())
        // текстовые фильтры
        val buttons = ArrayList<String>()
        val contains = ArrayList<String>()
        call?.kwargs?.get("text")?.let { te ->
            when (te) {
                is PyExpr.Str -> buttons.add(te.value)
                is PyExpr.Seq -> te.items.forEach { (it as? PyExpr.Str)?.let { s -> buttons.add(s.value) } }
                else -> {}
            }
        }
        call?.kwargs?.get("regexp")?.let { (it as? PyExpr.Str)?.value }?.let { rx ->
            val plain = rx.trim('^', '$').replace("(?i)", "")
            if (plain.isNotBlank() && plain.none { it in "[]()*+?{}|\\" }) contains.add(plain)
            else warnings.add("Фильтр regexp=\"$rx\" слишком сложный — правило не создано")
        }
        textFilters(region.first, region.last + 1, buttons, contains)
        if (buttons.isEmpty() && contains.isEmpty()) return Deco(callback = false)
        return Deco(callback = false, buttons = buttons.distinct(), contains = contains.distinct())
    }

    /** callback_data из фильтра: c.data == "x", F.data == "x", data in ("a", "b"). */
    private fun dataIds(start: Int, end: Int): List<String> {
        val out = ArrayList<String>()
        val m = src.masked.substring(start, end)
        if (!m.contains("data")) return out
        for (s in src.strings.filter { it.start >= start && it.end <= end }) {
            val before = src.masked.substring(maxOf(start, s.start - 40), s.start)
            val after = src.masked.substring(s.end, minOf(end, s.end + 40))
            if (Regex("""data\s*==\s*$""").containsMatchIn(before) ||
                Regex("""^\s*==\s*[\w.]*data\b""").containsMatchIn(after) ||
                Regex("""data\s+in\s*[(\[{][^)\]}]*$""").containsMatchIn(before) ||
                Regex("""data\.in_\(\s*[(\[{]?[^)\]}]*$""").containsMatchIn(before) ||
                Regex("""(?:Text|text)\s*[(=]\s*(?:equals\s*=\s*)?$""").containsMatchIn(before)) {
                out.add(s.value)
            }
        }
        return out.distinct()
    }

    /** Текстовые фильтры: m.text == "…", m.text in […], "…" in m.text.lower(), F.text == "…". */
    private fun textFilters(start: Int, end: Int, buttons: MutableList<String>, contains: MutableList<String>) {
        val m = src.masked.substring(start, end)
        if (!m.contains("text") && !m.contains("Text")) return
        // «not (…startswith("/"))» и подобное — это «всё остальное» (ИИ), не правило
        val negated = Regex("""(?<![\w.])not\b|!=""").containsMatchIn(m)
        for (s in src.strings.filter { it.start >= start && it.end <= end }) {
            val before = src.masked.substring(maxOf(start, s.start - 60), s.start)
            val after = src.masked.substring(s.end, minOf(end, s.end + 60))
            val v = s.value
            when {
                Regex("""text(?:\.(?:lower|strip|casefold|upper)\(\))*\s*==\s*$""").containsMatchIn(before) -> buttons.add(v)
                Regex("""^\s*==\s*[\w.]*text\b""").containsMatchIn(after) -> buttons.add(v)
                Regex("""text(?:\.(?:lower|strip|casefold)\(\))*\s+in\s*[(\[{][^)\]}]*$""").containsMatchIn(before) -> buttons.add(v)
                Regex("""text\.in_\(\s*[(\[{]?[^)\]}]*$""").containsMatchIn(before) -> buttons.add(v)
                negated -> {}
                Regex("""^\s*in\s+[\w.()"' ]*text""").containsMatchIn(after) -> contains.add(v)
                Regex("""(?:startswith|endswith|contains|__contains__)\(\s*$""").containsMatchIn(before) -> contains.add(v)
                Regex("""Text\(\s*(?:equals|text)\s*=\s*$""").containsMatchIn(before) -> buttons.add(v)
                Regex("""Text\(\s*(?:contains|startswith)\s*=\s*$""").containsMatchIn(before) -> contains.add(v)
                Regex("""for\s+\w+\s+in\s*[(\[{][^)\]}]*$""").containsMatchIn(before) &&
                    Regex("""\w+\s+in\s+[\w.()]*text""").containsMatchIn(m) -> contains.add(v)
            }
        }
        contains.removeAll { it.isBlank() || it == "/" }
        // m.text in BUTTON_LABELS (константа-список)
        Regex("""text\s+in\s+([A-Z_][A-Z0-9_]*)""").findAll(m).forEach { mm ->
            listConsts[mm.groupValues[1]]?.let { buttons.addAll(it) }
        }
    }

    // ------------------------------------------------------------------
    // обработчики нажатий
    // ------------------------------------------------------------------

    private fun collectCallbackBranches() {
        for (f in src.functions) {
            for (deco in f.decoratorRanges) {
                val d = decoratorInfo(deco) ?: continue
                if (!d.callback) continue
                if (!d.dispatcher) {
                    d.callbackIds.forEach { id -> cbBranches.putIfAbsent(id, Branch(f, f.body, f.inlineBody)) }
                    continue
                }
                // диспетчер: ветки if/elif call.data == "…" и match/case
                for (line in f.body) {
                    val ml = src.maskedLine(line)
                    val isIf = ml.startsWith("if ") || ml.startsWith("elif ")
                    val isCase = ml.startsWith("case ")
                    if (!isIf && !isCase) continue
                    val colon = src.headerColon(line)
                    if (colon < 0) continue
                    val ids = if (isCase) src.strings.filter { it.start > line.start && it.end <= colon }.map { it.value }
                    else dataIds(line.start, colon)
                    if (ids.isEmpty()) continue
                    val inline = if (src.masked.substring(colon + 1, line.end).isNotBlank()) (colon + 1) until line.end else null
                    val block = src.blockOf(line)
                    ids.forEach { id -> cbBranches.putIfAbsent(id, Branch(f, block, inline)) }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // анализ тела обработчика
    // ------------------------------------------------------------------

    private sealed class Ev(val off: Int) {
        /** Всплывашка; [beforeReturn] — сразу за ней в коде стоит «return». */
        class Toast(off: Int, val text: String, val beforeReturn: Boolean = false) : Ev(off)
        class Return(off: Int) : Ev(off)
        class Send(off: Int, val textE: PyExpr?, val markup: PyExpr?, val fixedMarkup: String?) : Ev(off)
        class Edit(off: Int, val textE: PyExpr?, val markup: PyExpr?, val fixedMarkup: String?) : Ev(off)
        class Flag(off: Int, val value: Boolean) : Ev(off)
        class Llm(off: Int) : Ev(off)
        class Script(off: Int, val code: String) : Ev(off)
        class Anim(off: Int, val spec: AnimSpec, val markup: PyExpr?, val edit: Boolean) : Ev(off)
        class Dice(off: Int, val emoji: String) : Ev(off)
    }

    private val runLikeRe = Regex("""(?i)(run|remind|schedul|active|enabled|started|working|notif|alarm|paused)""")
    private val msgRecvRe = Regex("""(^|\.)(message|msg|m)\.(answer|reply)$""")
    private val cbRecvRe = Regex("""^(call|callback|query|c|cb|callback_query|q)\.answer$""")

    private fun analyze(f: PySource.PyFunc, lines: List<PySource.LLine>, inline: IntRange?): Action {
        val ranges = ArrayList<IntRange>()
        inline?.let { ranges.add(it) }
        lines.forEach { ranges.add(it.start until it.end) }
        val globals = HashSet<String>()
        f.body.forEach { l ->
            val ml = src.maskedLine(l)
            if (ml.startsWith("global ")) ml.removePrefix("global ").split(',').forEach { globals.add(it.trim()) }
        }
        val evs = ArrayList<Ev>()
        val seen = HashSet<Int>()
        for (r in ranges) {
            // «return» внутри ветки: после досрочного выхода («уже работает»)
            Regex("""(?m)^\s*return\b""").findAll(src.masked.substring(r.first, r.last + 1))
                .forEach { m -> evs.add(Ev.Return(r.first + m.range.first)) }
            // флаги напоминаний: bot_running = True / False
            Regex("""(?m)^\s*([A-Za-z_]\w*)\s*=\s*(True|False)\s*$""")
                .findAll(src.masked.substring(r.first, r.last + 1)).forEach { m ->
                    val name = m.groupValues[1]
                    if (name in globals || runLikeRe.containsMatchIn(name)) {
                        evs.add(Ev.Flag(r.first + m.range.first, m.groupValues[2] == "True"))
                    }
                }
            for (c in src.calls(r.first, r.last + 1)) {
                if (!seen.add(c.start)) continue
                val h = helpers[c.fn] ?: helpers[c.shortFn]
                when {
                    c.fn in animHelpers || c.shortFn in animHelpers ->
                        animFromCall(c, f)?.let { evs.add(it) }
                    c.shortFn == "send_dice" -> {
                        val e = strOf(c.kwargs["emoji"] ?: c.args.getOrNull(1), f).orEmpty()
                        evs.add(Ev.Dice(c.start, e.ifBlank { "🎲" }))
                    }
                    c.shortFn == "answer_callback_query" ->
                        strOf(c.arg(1, "text"), f)?.let { evs.add(Ev.Toast(c.start, it)) }
                    h?.kind == Kind.TOAST -> strOf(c.args.getOrNull(h.textIndex), f)?.let { evs.add(Ev.Toast(c.start, it)) }
                    cbRecvRe.matches(c.fn) -> strOf(c.arg(0, "text"), f)?.let { evs.add(Ev.Toast(c.start, it)) }
                    c.shortFn == "send_message" || c.shortFn == "reply_to" ->
                        evs.add(Ev.Send(c.start, c.arg(1, "text"), c.kwargs["reply_markup"], null))
                    h?.kind == Kind.SEND -> evs.add(Ev.Send(c.start, c.args.getOrNull(h.textIndex),
                        c.kwargs["reply_markup"] ?: c.args.getOrNull(h.markupIndex), h.fixedMarkup))
                    msgRecvRe.containsMatchIn(c.fn) ->
                        evs.add(Ev.Send(c.start, c.arg(0, "text"), c.kwargs["reply_markup"], null))
                    c.shortFn == "edit_message_text" || c.fn.endsWith(".edit_text") ->
                        evs.add(Ev.Edit(c.start, c.arg(0, "text"), c.kwargs["reply_markup"], null))
                    h?.kind == Kind.EDIT -> evs.add(Ev.Edit(c.start,
                        if (h.textIndex >= 0) c.args.getOrNull(h.textIndex)
                        else c.args.firstOrNull { it !is PyExpr.Name || it.id !in setOf("call", "c", "callback", "query") },
                        c.kwargs["reply_markup"] ?: c.args.getOrNull(h.markupIndex), h.fixedMarkup))
                    h?.kind == Kind.LLM || llmNameRe.containsMatchIn(c.shortFn) -> evs.add(Ev.Llm(c.start))
                    c.shortFn == "run_script" -> strOf(c.arg(0, "code"), f)?.let { evs.add(Ev.Script(c.start, it)) }
                }
            }
        }
        evs.sortWith(compareBy({ it.off }, { if (it is Ev.Return) 1 else 0 }))
        // помечаем всплывашки, после которых сразу «return»
        val toasts = ArrayList<Ev.Toast>()
        evs.forEachIndexed { i, ev ->
            if (ev is Ev.Toast) {
                val nxt = evs.getOrNull(i + 1)
                toasts.add(Ev.Toast(ev.off, ev.text, nxt is Ev.Return))
            }
        }
        // Анимация: вызов помощника или цикл правок прямо в обработчике.
        val anim = evs.filterIsInstance<Ev.Anim>().firstOrNull() ?: inlineAnim(f, ranges, evs)
        if (anim != null) {
            return Action(kind = "anim", anim = anim.spec, edit = anim.edit,
                toast = toasts.firstOrNull()?.text.orEmpty(),
                markup = markupRef(anim.markup, null, f))
        }
        evs.filterIsInstance<Ev.Dice>().firstOrNull()?.let { d ->
            return Action(kind = "dice", text = d.emoji, toast = toasts.firstOrNull()?.text.orEmpty())
        }
        val flag = evs.filterIsInstance<Ev.Flag>().lastOrNull()
        if (flag != null) {
            val edit = evs.filterIsInstance<Ev.Edit>().firstOrNull { it.off > flag.off }
                ?: evs.filterIsInstance<Ev.Edit>().firstOrNull()
            val send = evs.filterIsInstance<Ev.Send>().firstOrNull { it.off > flag.off }
            val tv = resolveText(edit?.textE ?: send?.textE, f)
            // Всплывашка перед «return» — это ответ на повторное нажатие
            // («Уже работает» / «Уже остановлен»), остальные — основной отклик.
            val earlyOut = toasts.filter { it.beforeReturn }
            val normal = toasts.filter { !it.beforeReturn }
            val success = normal.lastOrNull { it.off < flag.off }?.text
                ?: normal.firstOrNull { it.off > flag.off }?.text
                ?: toasts.lastOrNull()?.text.orEmpty()
            val noChange = earlyOut.firstOrNull()?.text?.takeIf { it != success }
                ?: toasts.firstOrNull()?.text?.takeIf { it != success }.orEmpty()
            return Action(
                kind = if (flag.value) "on" else "off",
                text = if (tv.kind == "text") tv.text else "",
                edit = true,
                toast = success,
                toastNoChange = noChange,
                markup = markupRef(edit?.markup, edit?.fixedMarkup, f),
            )
        }
        val chosen = evs.firstOrNull { it is Ev.Send || it is Ev.Edit }
        val toast = toasts.firstOrNull()?.text.orEmpty()
        if (chosen != null) {
            val (textE, markupE, fixed) = when (chosen) {
                is Ev.Send -> Triple(chosen.textE, chosen.markup, chosen.fixedMarkup)
                is Ev.Edit -> Triple(chosen.textE, chosen.markup, chosen.fixedMarkup)
                else -> Triple(null, null, null)
            }
            val tv = resolveText(textE, f)
            val kind = when (tv.kind) {
                "unknown" -> if (evs.any { it is Ev.Llm }) "llm" else "text"
                "none" -> "none"
                else -> tv.kind
            }
            if (tv.kind == "unknown" && kind == "text") {
                warnings.add("${f.name}(): текст ответа вычисляется в коде — перенесён как «…», поправь в правилах")
            }
            return Action(kind = kind, text = if (kind == "text") tv.text.ifBlank { if (tv.kind == "unknown") "…" else "" } else "",
                pack = tv.pack, script = tv.script, edit = chosen is Ev.Edit, toast = toast,
                markup = markupRef(markupE, fixed, f))
        }
        evs.firstOrNull { it is Ev.Script }?.let { return Action(kind = "script", script = (it as Ev.Script).code, toast = toast) }
        if (evs.any { it is Ev.Llm }) return Action(kind = "llm", toast = toast)
        return Action(kind = "none", toast = toast)
    }

    private fun resolveText(e: PyExpr?, f: PySource.PyFunc, depth: Int = 0): TextVal {
        if (e == null) return TextVal("none")
        if (depth > 5) return TextVal("unknown")
        return when (e) {
            is PyExpr.Str -> TextVal("text", normalizeText(e.value))
            is PyExpr.Name -> {
                strConsts[e.id]?.let { return TextVal("text", normalizeText(it)) }
                localExpr(f, e.id, e.start)?.let { return resolveText(it, f, depth + 1) }
                TextVal("unknown")
            }
            is PyExpr.Call -> when {
                e.shortFn == "choice" -> {
                    val arg = e.args.firstOrNull() as? PyExpr.Name
                    if (arg != null && arg.id in listConsts) TextVal("pack", pack = arg.id)
                    else TextVal("unknown")
                }
                packHelpers[e.fn] != null -> TextVal("pack", pack = packHelpers[e.fn]!!)
                helpers[e.fn]?.kind == Kind.LLM || llmNameRe.containsMatchIn(e.shortFn) -> TextVal("llm")
                e.shortFn == "run_script" -> TextVal("script", script = strOf(e.args.firstOrNull(), f).orEmpty())
                else -> TextVal("unknown")
            }
            else -> {
                val m = src.maskedOf(e)
                Regex("""choice\(\s*([A-Za-z_]\w*)\s*\)""").find(m)?.let { mm ->
                    if (mm.groupValues[1] in listConsts) return TextVal("pack", pack = mm.groupValues[1])
                }
                Regex("""([A-Za-z_]\w*)\s*\(""").findAll(m).forEach { mm ->
                    val name = mm.groupValues[1]
                    packHelpers[name]?.let { return TextVal("pack", pack = it) }
                    if (helpers[name]?.kind == Kind.LLM || llmNameRe.containsMatchIn(name)) return TextVal("llm")
                }
                TextVal("unknown")
            }
        }
    }

    private fun markupRef(e: PyExpr?, fixed: String?, f: PySource.PyFunc): MarkupRef? {
        when (e) {
            is PyExpr.Call -> keyboards[e.fn]?.let { return MarkupRef(e.fn, it) }
                ?: keyboards[e.shortFn]?.let { return MarkupRef(e.shortFn, it) }
            is PyExpr.Name -> {
                keyboards[e.id]?.let { return MarkupRef(e.id, it) }
                val local = localExpr(f, e.id, e.start)
                if (local is PyExpr.Call) keyboards[local.fn]?.let { return MarkupRef(local.fn, it) }
                parseKeyboard(f, e.id)?.let { if (it.rows.isNotEmpty()) return MarkupRef(null, it) }
            }
            else -> {}
        }
        if (fixed != null) keyboards[fixed]?.let { return MarkupRef(fixed, it) }
        return null
    }

    /** Разметка ответа → (inline-меню правила, клавиатура чата). */
    private fun splitMarkup(ref: MarkupRef?, owner: String): Pair<List<InlineBtn>, MarkupRef?> {
        ref ?: return emptyList<InlineBtn>() to null
        ref.fn?.let { attachedKeyboards.add(it) }
        return if (ref.kb.inline) inlineMenu(ref.kb, ref.fn) to null else emptyList<InlineBtn>() to ref
    }

    private fun ruleFromAction(a: Action, menu: List<InlineBtn>): ScriptImporter.ImportedRule =
        ScriptImporter.ImportedRule(
            type = "command", pattern = "",
            actionType = when (a.kind) {
                "pack" -> "pack"; "llm" -> "llm"; "script" -> "script"; "anim" -> "anim"; "dice" -> "dice"
                else -> "text"
            },
            text = if (a.kind == "text" || a.kind == "none" || a.kind == "on" || a.kind == "off" ||
                a.kind == "dice") a.text else "",
            packId = if (a.kind == "pack") "imp_${a.pack}" else "",
            script = if (a.kind == "anim" && a.anim != null) Anim.encode(a.anim) else a.script,
            menu = menu,
        )

    // ------------------------------------------------------------------
    // inline-меню: кнопки + действия из обработчиков нажатий
    // ------------------------------------------------------------------

    private val actionCache = HashMap<String, Action?>()

    private fun callbackAction(id: String): Action? = actionCache.getOrPut(id) {
        val br = cbBranches[id] ?: return@getOrPut null
        analyze(br.func, br.lines, br.inline)
    }

    private fun inlineMenu(kb: Keyboard, ownerFn: String?): List<InlineBtn> {
        val out = ArrayList<InlineBtn>()
        kb.rows.forEachIndexed { r, row ->
            row.forEach { b ->
                val label = b.label.take(64)
                if (b.url != null) {
                    val slug = label.filter { it.isLetterOrDigit() }.take(12).lowercase()
                        .ifBlank { "link" }
                    out.add(InlineBtn(id = "url_$slug", label = label, action = "url",
                        url = b.url, row = r + 1))
                    return@forEach
                }
                val id = (b.callback ?: label).take(64)
                val act = callbackAction(id)
                if (act == null) {
                    // Штатные кнопки без обработчика (частый случай в экспортах и
                    // коротких конфигах): start/stop → вкл/выкл напоминаний.
                    val builtin = builtinToggle(id)
                    if (builtin != null) {
                        warnings.add("ℹ️ Кнопка «$label» (callback_data=\"$id\"): обработчика нет — " +
                            "назначено штатное «${if (builtin) "включить" else "выключить"} напоминания»")
                        out.add(InlineBtn(id = id, label = label,
                            toast = if (builtin) "Запущено" else "Остановлено",
                            action = if (builtin) "reminders_on" else "reminders_off",
                            text = if (builtin) "▶️ Напоминания включены." else "⏹ Напоминания выключены.",
                            toastNoChange = if (builtin) "Уже работает" else "Уже остановлено",
                            row = r + 1))
                        return@forEach
                    }
                    warnings.add("Кнопка «$label» (callback_data=\"$id\"): обработчик нажатия не найден — задай действие после импорта")
                    out.add(InlineBtn(id = id, label = label, action = "text", row = r + 1))
                    return@forEach
                }
                if (act.markup != null && act.markup.fn != ownerFn && act.markup.kb.inline &&
                    act.markup.fn != null) {
                    attachedKeyboards.add(act.markup.fn)
                    warnings.add("Кнопка «$label» открывает другое меню ${act.markup.fn}() — вложенные меню пока не поддерживаются")
                }
                out.add(when (act.kind) {
                    "on", "off" -> InlineBtn(id = id, label = label, toast = act.toast,
                        action = if (act.kind == "on") "reminders_on" else "reminders_off",
                        text = act.text, toastNoChange = act.toastNoChange, row = r + 1)
                    "pack" -> InlineBtn(id = id, label = label, toast = act.toast, action = "pack",
                        packId = "imp_${act.pack}", edit = act.edit, row = r + 1)
                    "script" -> InlineBtn(id = id, label = label, toast = act.toast, action = "script",
                        script = act.script, row = r + 1)
                    "anim" -> InlineBtn(id = id, label = label, toast = act.toast, action = "anim",
                        script = act.anim?.let { Anim.encode(it) }.orEmpty(), edit = act.edit, row = r + 1)
                    "dice" -> InlineBtn(id = id, label = label, toast = act.toast, action = "dice",
                        text = act.text, row = r + 1)
                    "llm" -> {
                        warnings.add("Кнопка «$label» отвечает через ИИ — для кнопок это пока не поддерживается, задай текст")
                        InlineBtn(id = id, label = label, toast = act.toast, action = "text", row = r + 1)
                    }
                    else -> InlineBtn(id = id, label = label, toast = act.toast, action = "text",
                        text = act.text, edit = act.edit, row = r + 1)
                })
            }
        }
        return out
    }

    /** start/stop-подобные callback_data → true (вкл) / false (выкл) / null. */
    private fun builtinToggle(id: String): Boolean? = when (id.lowercase()) {
        "start", "run", "on", "resume", "go", "reminders_on", "remind_on" -> true
        "stop", "off", "pause", "reminders_off", "remind_off" -> false
        else -> null
    }

    /** Списки, которые оказались кадрами анимации, — это не наборы ответов. */
    private val animFrameNames = HashSet<String>()

    /** Кадры из выражения: список-константа или литерал списка строк. */
    private fun framesOf(e: PyExpr?, f: PySource.PyFunc): List<String>? = when (e) {
        is PyExpr.Name -> listConsts[e.id]?.also { animFrameNames.add(e.id) }
            ?: (localExpr(f, e.id, e.start) as? PyExpr.Seq)?.items?.mapNotNull { (it as? PyExpr.Str)?.value }
        is PyExpr.Seq -> e.items.mapNotNull { (it as? PyExpr.Str)?.value }.ifEmpty { null }
        else -> null
    }

    /**
     * play_animation(chat_id, FRAMES, 0.5, "итог", reply_markup=kb()) или с
     * ключами frames= / delay= / interval= / final= / preset= / text= / loops=.
     * Вместо кадров можно имя шаблона строкой: "spinner", "progress", …
     */
    private fun animFromCall(c: PyExpr.Call, f: PySource.PyFunc): Ev.Anim? {
        var frames: List<String>? = framesOf(c.kwargs["frames"], f)
        var preset: String? = strOf(c.kwargs["preset"], f)?.takeIf { p -> Anim.PRESETS.any { it.id == p } }
        var delay: Double? = (c.kwargs["delay"] ?: c.kwargs["interval"] ?: c.kwargs["seconds"])
            .let { (it as? PyExpr.Num)?.value }
        var final: String? = strOf(c.kwargs["final"] ?: c.kwargs["final_text"], f)
        val text: String = strOf(c.kwargs["text"] ?: c.kwargs["label"], f).orEmpty()
        val loops = ((c.kwargs["loops"] ?: c.kwargs["repeat"]) as? PyExpr.Num)?.value?.toInt() ?: 1
        // pack=НАБОР — финал из набора (слот-машина); mono=True — моноширинно
        val packId = when (val pk = c.kwargs["pack"]) {
            is PyExpr.Name -> "imp_${pk.id}"
            is PyExpr.Str -> pk.value.trim().takeIf { it.isNotBlank() }?.let { "imp_$it" }
            else -> null
        }.orEmpty()
        val monoKw = (c.kwargs["mono"] as? PyExpr.Const)?.value
        for (a in c.args) {
            when {
                frames == null && preset == null && framesOf(a, f) != null -> frames = framesOf(a, f)
                a is PyExpr.Num && delay == null -> delay = a.value
                a is PyExpr.Str || (a is PyExpr.Name && a.id in strConsts) -> {
                    val v = strOf(a, f).orEmpty()
                    if (frames == null && preset == null && Anim.PRESETS.any { it.id == v }) preset = v
                    else if (final == null) final = v
                }
            }
        }
        if (frames.isNullOrEmpty() && preset == null) return null
        val base = Anim.preset(preset ?: "custom")
        val spec = AnimSpec(
            preset = preset ?: "custom",
            text = text.ifBlank { if (preset != null) base.defaultText else "" },
            frames = frames.orEmpty().map { normalizeText(it) },
            intervalMs = ((delay ?: (base.defaultInterval / 1000.0)) * 1000).toInt()
                .coerceIn(Anim.MIN_INTERVAL, Anim.MAX_INTERVAL),
            loops = loops.coerceIn(1, 10),
            finalText = final?.let { normalizeText(it) }.orEmpty(),
            mono = monoKw ?: if (preset != null) base.mono else frames.orEmpty().let { fr ->
                fr.any { '\n' in it } && fr.none { Anim.photoSrc(it) != null } // фото-кадры — подписи, не ASCII
            },
            packId = packId,
        )
        val edit = c.kwargs.containsKey("message_id")
        return Ev.Anim(c.start, spec, c.kwargs["reply_markup"], edit)
    }

    /**
     * Цикл прямо в обработчике:
     *   m = bot.send_message(chat, FRAMES[0])
     *   for frame in FRAMES[1:]: time.sleep(0.5); bot.edit_message_text(frame, chat, m.message_id)
     *   bot.edit_message_text("итог", chat, m.message_id, reply_markup=kb())
     */
    private fun inlineAnim(f: PySource.PyFunc, ranges: List<IntRange>, evs: List<Ev>): Ev.Anim? {
        if (ranges.isEmpty()) return null
        // Строки тела идут отдельными диапазонами, а цикл и правка кадра —
        // на разных строках: смотрим весь участок обработчика целиком.
        val span = ranges.minOf { it.first }..ranges.maxOf { it.last }
        for (r in listOf(span)) {
            val m = src.masked.substring(r.first, r.last + 1)
            val loop = Regex("""(?m)^\s*for\s+(\w+)\s+in\s+([A-Za-z_]\w*)""").find(m) ?: continue
            val loopVar = loop.groupValues[1]
            val frames = listConsts[loop.groupValues[2]] ?: continue
            animFrameNames.add(loop.groupValues[2])
            val loopOff = r.first + loop.range.first
            val edits = evs.filterIsInstance<Ev.Edit>().filter { it.off in r }
            if (edits.none { it.off > loopOff }) continue
            val delay = Regex("""sleep\(\s*([0-9.]+)\s*\)""").find(m)?.groupValues?.get(1)?.toDoubleOrNull()
            val finalEdit = edits.lastOrNull { e ->
                e.off > loopOff && !(e.textE is PyExpr.Name && (e.textE as PyExpr.Name).id == loopVar)
            }
            val final = finalEdit?.textE?.let { strOf(it, f) }
            val sendMarkup = evs.filterIsInstance<Ev.Send>().firstOrNull { it.off in r }?.markup
            val spec = AnimSpec(
                preset = "custom",
                frames = frames.map { normalizeText(it) },
                intervalMs = ((delay ?: 0.7) * 1000).toInt().coerceIn(Anim.MIN_INTERVAL, Anim.MAX_INTERVAL),
                finalText = final?.let { normalizeText(it) }.orEmpty(),
                mono = frames.any { '\n' in it } && frames.none { Anim.photoSrc(it) != null },
            )
            return Ev.Anim(loopOff, spec, finalEdit?.markup ?: sendMarkup, false)
        }
        return null
    }

    // ------------------------------------------------------------------
    // расписание
    // ------------------------------------------------------------------

    private val ALL_DAYS = (1..7).toSet()
    private val tupleRe = Regex("""\(\s*\d{1,2}\s*,\s*\d{1,2}\s*,""")

    private data class RawEvent(val h: Int, val m: Int, val textE: PyExpr, val kbE: PyExpr?, val days: Set<Int>, val f: PySource.PyFunc?)

    private fun scheduleEvents(): List<ScheduleEvent> {
        val raw = ArrayList<RawEvent>()
        val schedFns = src.functions.filter { Regex("""(?i)sched|remind|event|timetable|распис""").containsMatchIn(it.name) }
        for (f in schedFns) raw.addAll(walkSchedule(f))
        if (raw.isEmpty()) {
            for ((name, e) in moduleExprs) {
                if (!Regex("""(?i)sched|events|remind""").containsMatchIn(name)) continue
                raw.addAll(tuplesIn(e.start, e.end).map { RawEvent(it.first, it.second, it.third, it.fourth, ALL_DAYS, null) })
            }
        }
        if (raw.isEmpty()) {
            // запасной путь (старые конфиги): кортежи по всему файлу
            for (f in src.functions) raw.addAll(walkSchedule(f))
            val inFuncs = raw.map { it.textE.start }.toSet()
            raw.addAll(tuplesIn(0, src.masked.length).filter { it.third.start !in inFuncs }
                .map { RawEvent(it.first, it.second, it.third, it.fourth, ALL_DAYS, null) })
        }
        val conditional = conditionalMenus()
        val out = ArrayList<ScheduleEvent>()
        for (ev in raw) {
            val f = ev.f
            var text = when (val e = ev.textE) {
                is PyExpr.Str -> normalizeText(e.value)
                is PyExpr.Name -> strConsts[e.id]?.let { normalizeText(it) }
                    ?: (localExpr(f, e.id, e.start) as? PyExpr.Str)?.value
                else -> null
            } ?: continue
            if (ev.h !in 0..23 || ev.m !in 0..59 || ev.days.isEmpty()) continue
            var toChannel = false
            if (text.startsWith("»канал")) {
                toChannel = true
                text = text.removePrefix("»канал").trim()
            }
            var menu: List<InlineBtn> = emptyList()
            val kbName = when (val k = ev.kbE) {
                is PyExpr.Call -> k.fn
                is PyExpr.Name -> k.id
                is PyExpr.Str -> k.value
                else -> null
            }
            if (kbName != null) {
                val kb = keyboards[kbName]
                if (kb != null && kb.inline) {
                    attachedKeyboards.add(kbName)
                    menu = inlineMenu(kb, kbName)
                } else if (ev.kbE !is PyExpr.Const) {
                    warnings.add("Событие ${"%02d:%02d".format(ev.h, ev.m)}: меню $kbName() не найдено")
                }
            } else {
                conditional.firstOrNull { (kw, _) -> kw.any { text.contains(it, ignoreCase = true) } }?.let { (_, fn) ->
                    keyboards[fn]?.let { kb ->
                        attachedKeyboards.add(fn)
                        menu = inlineMenu(kb, fn)
                    }
                }
            }
            out.add(ScheduleEvent(
                id = "imp_${ev.h}_${ev.m}_${out.size}",
                hour = ev.h, minute = ev.m, text = text,
                days = ev.days.sorted(), menu = menu, toChannel = toChannel,
            ))
        }
        return out.distinctBy { it.days.joinToString() + "|" + (it.hour * 60 + it.minute) + "|" + it.text }
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    /** Кортежи (ЧАС, МИН, текст[, меню]) в диапазоне. */
    private fun tuplesIn(start: Int, end: Int): List<Quad<Int, Int, PyExpr, PyExpr?>> {
        val out = ArrayList<Quad<Int, Int, PyExpr, PyExpr?>>()
        for (m in tupleRe.findAll(src.masked.substring(start, end))) {
            val open = start + m.range.first
            val close = src.matchClose(open)
            if (close < 0 || close >= end) continue
            val e = src.expr(open, close + 1) as? PyExpr.Seq ?: continue
            if (e.items.size < 3) continue
            val h = (e.items[0] as? PyExpr.Num)?.value?.toInt() ?: continue
            val mi = (e.items[1] as? PyExpr.Num)?.value?.toInt() ?: continue
            val te = e.items[2]
            if (te !is PyExpr.Str && te !is PyExpr.Name) continue
            out.add(Quad(h, mi, te, e.items.getOrNull(3)))
        }
        return out
    }

    /** Обход тела функции расписания с учётом условий по дню недели. */
    private fun walkSchedule(f: PySource.PyFunc): List<RawEvent> {
        val out = ArrayList<RawEvent>()
        val wdVars = HashMap<String, Boolean>() // имя → isoweekday?
        f.body.forEach { l ->
            val a = src.assignment(l) ?: return@forEach
            val m = src.maskedOf(a.second)
            if (m.contains(".isoweekday()")) wdVars[a.first] = true
            else if (m.contains(".weekday()")) wdVars[a.first] = false
        }
        val stack = ArrayList<Pair<Int, Set<Int>>>()
        val chainRem = HashMap<Int, Set<Int>>()
        fun add(start: Int, end: Int, days: Set<Int>) {
            tuplesIn(start, end).forEach { out.add(RawEvent(it.first, it.second, it.third, it.fourth, days, f)) }
        }
        f.inlineBody?.let { add(it.first, it.last + 1, ALL_DAYS) }
        for (line in f.body) {
            while (stack.isNotEmpty() && stack.last().first >= line.indent) stack.removeAt(stack.size - 1)
            chainRem.keys.filter { it > line.indent }.forEach { chainRem.remove(it) }
            val parent = stack.lastOrNull()?.second ?: ALL_DAYS
            val ml = src.maskedLine(line)
            val colon = src.headerColon(line)
            val kw = Regex("""^(if|elif|else|for|while|with|try|except|finally)\b""").find(ml)?.groupValues?.get(1)
            if (kw != null && colon >= 0) {
                val days: Set<Int> = when (kw) {
                    "if", "elif" -> {
                        val base = if (kw == "elif") chainRem[line.indent] ?: parent else parent
                        val condStart = line.start + line.indent + kw.length
                        val cond = evalDays(src.masked.substring(condStart, colon), wdVars)
                        if (cond == null) {
                            chainRem[line.indent] = base
                            base
                        } else {
                            chainRem[line.indent] = base - cond
                            base intersect cond
                        }
                    }
                    "else" -> (chainRem[line.indent] ?: parent).also { chainRem[line.indent] = emptySet() }
                    else -> { chainRem.remove(line.indent); parent }
                }
                stack.add(line.indent to days)
                if (src.masked.substring(colon + 1, line.end).isNotBlank()) add(colon + 1, line.end, days)
            } else {
                chainRem.remove(line.indent)
                add(line.start, line.end, parent)
            }
        }
        return out
    }

    /** Условие по дню недели → множество дней (1=Пн … 7=Вс) или null, если это не про день недели. */
    private fun evalDays(cond: String, vars: Map<String, Boolean>): Set<Int>? {
        var c = cond.replace(Regex("""[\w.()]*\.isoweekday\(\)"""), " WDI ")
            .replace(Regex("""[\w.()]*\.weekday\(\)"""), " WD ")
        vars.forEach { (name, iso) ->
            c = c.replace(Regex("""(?<![\w.])${Regex.escape(name)}(?![\w(])"""), if (iso) " WDI " else " WD ")
        }
        if (!c.contains("WD")) return null
        val tokens = Regex("""\d+|[A-Za-z_]\w*|<=|>=|==|!=|<|>|[()\[\]{},]""").findAll(c).map { it.value }.toList()
        val result = HashSet<Int>()
        for (d in 0..6) {
            val v = try { CondEval(tokens, d).evaluate() } catch (_: Exception) { return null }
            if (v) result.add(d + 1)
        }
        return result
    }

    /** reply_markup = smoke_keyboard() if is_smoke_event(text) else None → (ключевые слова, меню). */
    private fun conditionalMenus(): List<Pair<List<String>, String>> {
        val out = ArrayList<Pair<List<String>, String>>()
        val re = Regex("""([A-Za-z_]\w*)\(\s*\)\s+if\s+(.+?)\s+else\b""")
        for (m in re.findAll(src.masked)) {
            val fn = m.groupValues[1]
            if (keyboards[fn]?.inline != true) continue
            val condStart = m.groups[2]!!.range.first
            val condEnd = m.groups[2]!!.range.last + 1
            val words = ArrayList<String>()
            src.strings.filter { it.start >= condStart && it.end <= condEnd }.forEach { words.add(it.value) }
            Regex("""([A-Za-z_]\w*)\(""").find(src.masked.substring(condStart, condEnd))?.let { pm ->
                src.function(pm.groupValues[1])?.let { pf ->
                    src.strings.filter { it.start >= pf.bodyStart && it.end <= pf.bodyEnd }
                        .filter { s -> Regex("""^\s*in\b""").containsMatchIn(src.masked.substring(s.end, minOf(src.masked.length, s.end + 10))) }
                        .forEach { words.add(it.value) }
                }
            }
            if (words.isNotEmpty()) out.add(words to fn)
        }
        return out
    }

    // ------------------------------------------------------------------
    // прочее
    // ------------------------------------------------------------------

    private fun menuCommands(rules: List<ScriptImporter.ImportedRule>): List<MenuCommand> {
        val out = ArrayList<MenuCommand>()
        for (c in src.calls(0, src.masked.length)) {
            if (c.shortFn != "set_my_commands") continue
            val seq = c.arg(0, "commands") as? PyExpr.Seq ?: continue
            for (item in seq.items) {
                val bc = item as? PyExpr.Call ?: continue
                val cmd = strOf(bc.arg(0, "command"), null)?.removePrefix("/")?.lowercase() ?: continue
                val desc = strOf(bc.arg(1, "description"), null).orEmpty()
                out.add(MenuCommand(cmd, desc.ifBlank { defaultDescription(cmd, rules) }))
            }
        }
        if (out.isNotEmpty()) return out.distinctBy { it.command }
        return rules.filter { it.type == "command" }.map { it.pattern.removePrefix("/") }
            .filter { Regex("^[a-z0-9_]{1,32}$").matches(it) }
            .map { MenuCommand(it, defaultDescription(it, rules)) }
    }

    private fun defaultDescription(cmd: String, rules: List<ScriptImporter.ImportedRule>): String =
        when (cmd) {
            "start" -> "Запустить бота"
            "help" -> "Помощь"
            "rules" -> "Правила"
            "menu" -> "Меню"
            "about" -> "О боте"
            "settings" -> "Настройки"
            "cancel" -> "Отмена"
            "stop" -> "Остановить"
            else -> {
                val r = rules.firstOrNull { it.pattern == "/$cmd" }
                when (r?.actionType) {
                    "anim" -> Anim.decode(r.script).preset.let { if (it == "custom") "🎞 Анимация" else Anim.preset(it).title }
                    "dice" -> "Бросить ${r.text.ifBlank { "🎲" }}"
                    else -> r?.text?.lineSequence()
                        ?.firstOrNull { it.isNotBlank() }?.trim()?.take(40)?.ifBlank { null } ?: "/$cmd"
                }
            }
        }

    private fun channelSetting(): String? {
        for (name in listOf("CHANNEL_ID", "CHANNEL", "CHANNEL_USERNAME", "CHANNEL_NAME")) {
            val v = strConsts[name]?.trim() ?: continue
            if (v.startsWith("@") || v.startsWith("-100")) return v
        }
        return null
    }

    private fun legacyPrompt(): String? {
        val m = Regex("""(?:systemPrompt|system_prompt)\s*[:=]\s*(["'`])((?:(?!\1)[\s\S])*)\1""").find(t)
            ?: return null
        return m.groupValues[2].replace("\\n", "\n").trim().ifBlank { null }
    }

    private fun firstInt(patterns: List<String>): Int? =
        patterns.firstNotNullOfOrNull { p -> Regex(p).find(t)?.groupValues?.get(1)?.toIntOrNull() }

    private fun firstFloat(patterns: List<String>): Float? =
        patterns.firstNotNullOfOrNull { p -> Regex(p).find(t)?.groupValues?.get(1)?.toFloatOrNull() }

    /** Подстановки в текстах: {name}/{first_name}/{msg.from_user.first_name} → {user}. */
    private fun normalizeText(s: String): String = s.replace(Regex("""\{([^{}\n]{1,60})\}""")) { m ->
        val inner = m.groupValues[1].trim()
        when {
            inner == "user" || inner == "text" || inner == "bot" -> m.value
            inner == "name" || inner == "username" || inner.endsWith("first_name") ||
                inner.endsWith("full_name") || inner.endsWith(".username") || inner == "user_name" -> "{user}"
            inner.endsWith(".text") || inner == "user_text" -> "{text}"
            else -> m.value
        }
    }
}

/** Вычислитель условий вида «wd < 5», «wd in (5, 6)», «not wd == 4 and wd != 0». */
private class CondEval(private val tokens: List<String>, private val day: Int) {
    private var pos = 0
    private fun peek(): String? = tokens.getOrNull(pos)
    private fun next(): String = tokens[pos++]

    fun evaluate(): Boolean {
        val v = toBool(orExpr())
        if (pos != tokens.size) throw IllegalStateException("лишние токены")
        return v
    }

    private fun toBool(v: Any): Boolean = when (v) {
        is Boolean -> v
        is Int -> v != 0
        is Set<*> -> v.isNotEmpty()
        else -> throw IllegalStateException()
    }

    private fun orExpr(): Any {
        var v = andExpr()
        while (peek() == "or") { next(); val r = andExpr(); v = toBool(v) || toBool(r) }
        return v
    }

    private fun andExpr(): Any {
        var v = notExpr()
        while (peek() == "and") { next(); val r = notExpr(); v = toBool(v) && toBool(r) }
        return v
    }

    private fun notExpr(): Any {
        if (peek() == "not") { next(); return !toBool(notExpr()) }
        return comparison()
    }

    private fun comparison(): Any {
        var left = operand()
        var result: Boolean? = null
        while (true) {
            val op = when (peek()) {
                "<", "<=", ">", ">=", "==", "!=", "in" -> next()
                "not" -> if (tokens.getOrNull(pos + 1) == "in") { next(); next(); "not in" } else break
                else -> break
            }
            val right = operand()
            val r = compare(left, op, right)
            result = (result ?: true) && r
            left = right
        }
        return result ?: left
    }

    private fun compare(a: Any, op: String, b: Any): Boolean = when (op) {
        "in" -> (b as Set<*>).contains(a)
        "not in" -> !(b as Set<*>).contains(a)
        "==" -> a == b
        "!=" -> a != b
        "<" -> (a as Int) < (b as Int)
        "<=" -> (a as Int) <= (b as Int)
        ">" -> (a as Int) > (b as Int)
        ">=" -> (a as Int) >= (b as Int)
        else -> throw IllegalStateException(op)
    }

    private fun operand(): Any {
        val tk = next()
        return when {
            tk == "WD" -> day
            tk == "WDI" -> day + 1
            tk == "True" -> true
            tk == "False" -> false
            tk.all { it.isDigit() } -> tk.toInt()
            tk == "range" -> {
                if (next() != "(") throw IllegalStateException()
                val a = (operand() as Int)
                var b: Int? = null
                if (peek() == ",") { next(); b = operand() as Int }
                if (next() != ")") throw IllegalStateException()
                if (b == null) (0 until a).toSet() else (a until b).toSet()
            }
            tk == "(" || tk == "[" || tk == "{" -> {
                val close = when (tk) { "(" -> ")"; "[" -> "]"; else -> "}" }
                if (tk == "(") {
                    val first = orExpr()
                    if (peek() == ")") { next(); return first }
                    val items = HashSet<Any>()
                    items.add(first)
                    while (peek() == ",") { next(); if (peek() == ")") break; items.add(orExpr()) }
                    if (next() != ")") throw IllegalStateException()
                    items
                } else {
                    val items = HashSet<Any>()
                    while (peek() != close) {
                        items.add(orExpr())
                        if (peek() == ",") next()
                    }
                    next()
                    items
                }
            }
            else -> throw IllegalStateException("токен $tk")
        }
    }
}
