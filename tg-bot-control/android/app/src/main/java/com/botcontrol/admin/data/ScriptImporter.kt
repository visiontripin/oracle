package com.botcontrol.admin.data

/**
 * Импорт настроек готового бота из исходника (Python: telebot/aiogram,
 * JavaScript, или просто .txt с параметрами).
 * Параметры раскладываются по категориям настроек автоматически:
 * характер ИИ, температура/токены, память диалога, «печатает…», кулдаун,
 * команды, приветствие /start, уточняющие вопросы, наборы ответов,
 * расписание напоминаний, кнопки.
 */
object ScriptImporter {

    data class Parsed(
        val systemPrompt: String? = null,
        val temperature: Float? = null,
        val maxTokens: Int? = null,
        val topK: Int? = null,
        val historyLimit: Int? = null,
        val cooldownSec: Int? = null,
        val typingSec: Int? = null,
        val commands: List<String> = emptyList(),
        val greeting: String? = null,
        val clarify: List<String> = emptyList(),
        val packs: List<ReplyPack> = emptyList(),
        val schedule: List<ScheduleEvent> = emptyList(),
        val buttons: List<String> = emptyList(),
        /** В коде есть inline-кнопки «Запуск/Стоп» — приветствию /start нужно меню. */
        val startMenu: Boolean = false,
    ) {
        fun isEmpty(): Boolean = systemPrompt == null && temperature == null &&
            maxTokens == null && topK == null && historyLimit == null &&
            cooldownSec == null && typingSec == null && commands.isEmpty() &&
            greeting == null && clarify.isEmpty() && packs.isEmpty() &&
            schedule.isEmpty() && buttons.isEmpty()

        fun summary(): List<String> = buildList {
            systemPrompt?.let { add("Характер ИИ (${it.length} симв.)") }
            temperature?.let { add("Температура: $it") }
            maxTokens?.let { add("Максимум токенов: $it") }
            topK?.let { add("Top-K: $it") }
            historyLimit?.let { add("Память диалога: $it реплик") }
            cooldownSec?.let { add("Пауза между ответами: ${it} с") }
            typingSec?.let { add("«Печатает…»: ${it} с") }
            if (commands.isNotEmpty()) add("Команды меню: ${commands.joinToString(", ").take(80)}")
            greeting?.let { add("Приветствие /start (${it.length} симв.)") }
            if (clarify.isNotEmpty()) add("Уточняющих вопросов: ${clarify.size}")
            packs.forEach { add("Набор «${it.name}»: ${it.items.size} ответов") }
            if (schedule.isNotEmpty()) {
                val weekdayCount = schedule.count { it.days == listOf(1, 2, 3, 4, 5) }
                add("Событий расписания: ${schedule.size}" +
                    (if (weekdayCount > 0) " (из них будних: $weekdayCount)" else ""))
            }
            if (buttons.isNotEmpty()) add("Inline-кнопки в коде: ${buttons.joinToString(", ").take(80)}")
        }
    }

    private val knownPacks = mapOf(
        "JOKES" to "😂 Шутки",
        "SMOKE_DONE_SARCASM" to "🚬 Сарказм: покурил",
        "SMOKE_DONE" to "🚬 Сарказм: покурил",
        "SMOKE_HEALTHY_SARCASM" to "💪 Сарказм: остался здоровым",
        "SMOKE_HEALTHY" to "💪 Сарказм: остался здоровым",
        "CLARIFY_QUESTIONS" to "CLARIFY",
        "CLARIFY" to "CLARIFY",
    )

    /** Главная точка входа: текст любого исходника → найденные настройки. */
    fun parse(source: String): Parsed {
        val t = source.replace("\r\n", "\n")
        return Parsed(
            systemPrompt = extractPrompt(t),
            temperature = firstFloat(t, listOf("""temperature["\s:=]+([0-9.]+)""")),
            maxTokens = firstInt(t, listOf(
                """"max_tokens"\s*:\s*(\d+)""",
                """LLM_MAX_TOKENS\s*=\s*(\d+)""",
                """max_tokens\s*=\s*(\d+)""",
                """maxTokens\s*[:=]\s*(\d+)""",
            )),
            topK = firstInt(t, listOf(
                """top_k["\s:=]+(\d+)""",
                """topK\s*[:=]\s*(\d+)""",
                """LLM_MAX_TOP_K\s*=\s*(\d+)""",
            )),
            historyLimit = firstInt(t, listOf("""MAX_HISTORY\s*=\s*(\d+)""", """HISTORY_LIMIT\s*=\s*(\d+)""")),
            cooldownSec = firstInt(t, listOf("""ANSWER_COOLDOWN\s*=\s*(\d+)""", """COOLDOWN\s*[:=]\s*(\d+)""")),
            typingSec = firstInt(t, listOf(
                """DEFAULT_TYPING_SECONDS\s*=\s*(\d+)""",
                """install_typing_plugin\([^)]*seconds\s*=\s*(\d+)""",
                """send_typing\([^)]*seconds\s*=\s*(\d+)""",
                """TYPING_SECONDS\s*=\s*(\d+)""",
            )),
            commands = extractCommands(t),
            greeting = extractGreeting(t),
            clarify = extractClarify(t),
            packs = extractPacks(t),
            schedule = extractScheduleZoned(t),
            buttons = Regex("InlineKeyboardButton\\(\\s*\"([^\"]+)\"")
                .findAll(t).map { it.groupValues[1] }.toList(),
            startMenu = t.contains("Запуск") && t.contains("Стоп") &&
                t.contains("callback_data"),
        )
    }

    // ---------- помощники ----------

    private fun firstInt(t: String, patterns: List<String>): Int? =
        patterns.firstNotNullOfOrNull { p -> Regex(p).find(t)?.groupValues?.get(1)?.toIntOrNull() }

    private fun firstFloat(t: String, patterns: List<String>): Float? =
        patterns.firstNotNullOfOrNull { p -> Regex(p).find(t)?.groupValues?.get(1)?.toFloatOrNull() }

    /** Разворачивает \n, \" и &#39; в строковых литералах. */
    private fun unescape(s: String): String =
        s.replace("\\n", "\n").replace("\\t", " ")
            .replace("\\\"", "\"").replace("\\'", "'")

    /** Все строковые литералы выражения (python-кортеж, конкатенация…). */
    private fun quotedParts(expression: String): List<String> =
        Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(expression)
            .map { unescape(it.groupValues[1]) }
            .toList()

    private fun extractPrompt(t: String): String? {
        // SYSTEM_PROMPT = ... до пустой строки или следующего присваивания
        val m = Regex("""SYSTEM_PROMPT\s*=\s*([\s\S]*?)(?=\n\s*\n|\n[A-Za-z_#]|\Z)""").find(t)
            ?: Regex("""(?:SYSTEM_PROMPT|systemPrompt|system_prompt)\s*[:=]\s*(["'`])((?:(?!\1)[\s\S])*)\1""").find(t)
        return when {
            m == null -> null
            m.groupValues.size > 2 && m.groupValues[2].isNotBlank() ->
                unescape(m.groupValues[2].replace("\\n", "\n")).trim().ifBlank { null }
            else -> quotedParts(m.groupValues[1]).joinToString("").trim().ifBlank { null }
        }
    }

    private fun extractCommands(t: String): List<String> {
        val out = LinkedHashSet<String>()
        // telebot: commands=["start", "help"]
        Regex("""commands\s*=\s*\[([^\]]*)]""").findAll(t).forEach { m ->
            Regex("[\"']([A-Za-z0-9_]+)[\"']").findAll(m.groupValues[1])
                .forEach { out.add(it.groupValues[1].lowercase()) }
        }
        // aiogram: CommandStart() / Command("start")
        if (Regex("""CommandStart\(\)""").containsMatchIn(t)) out.add("start")
        Regex("""Command\(\s*[\"']([A-Za-z0-9_]+)[\"']\s*\)""").findAll(t)
            .forEach { out.add(it.groupValues[1].lowercase()) }
        // node-telegram-bot-api: bot.onText(/\/start/
        Regex("""onText\(\s*/\\?/(\w+)""").findAll(t)
            .forEach { out.add(it.groupValues[1].lowercase()) }
        return out.filter { it.isNotBlank() }.toList()
    }

    private fun extractGreeting(t: String): String? {
        // def cmd_start(...): ... send_message(chat, "ТЕКСТ", reply_markup=...)
        val handler = Regex("def\\s+\\w*(?:start|Start)\\w*\\s*\\([^)]*\\)\\s*:").find(t)
            ?: return null
        val body = t.substring(handler.range.last + 1)
        val nextDef = Regex("\\ndef\\s").find(body)?.range?.first ?: body.length
        val fnBody = body.substring(0, minOf(nextDef, body.length))
        val send = Regex("send_(?:message|answer)").find(fnBody) ?: return null
        // Только текстовые аргументы до reply_markup/keyboard — иначе в текст
        // попадут надписи кнопок.
        val args = fnBody.substring(send.range.last + 1)
            .substringBefore("reply_markup").substringBefore("keyboard")
        return quotedParts(args).joinToString("").trim().take(2000).ifBlank { null }
    }

    /** Тексты обработчиков команд: def cmd_help / def cmd_xxx → «/xxx» → текст. */
    fun extractCommandTexts(t: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        Regex("def\\s+cmd_(\\w+)\\s*\\([^)]*\\)\\s*:").findAll(t).forEach { m ->
            val cmd = m.groupValues[1].lowercase()
            if (cmd == "start") return@forEach // приветствие обрабатывается отдельно
            val body = t.substring(m.range.last + 1)
            val nextDef = Regex("\\ndef\\s").find(body)?.range?.first ?: body.length
            val fnBody = body.substring(0, nextDef)
            val msgIdx = fnBody.indexOf("send_message")
            val ansIdx = fnBody.indexOf("send_answer")
            val start = when {
                msgIdx >= 0 -> msgIdx + 12
                ansIdx >= 0 -> ansIdx + 11
                else -> -1
            }
            if (start < 0) return@forEach
            val args = fnBody.substring(start)
                .substringBefore("reply_markup").substringBefore("keyboard")
            val text = quotedParts(args).joinToString("").trim().take(2000)
            if (text.isNotBlank()) out[cmd] = text
        }
        return out
    }

    /** Строгий список: между [ и ] — только строковые литералы (по одному в
     *  строке). Не «съедает» чужие списки и не ловит служебный код. */
    private val strictList = Regex(
        """(\w+)\s*=\s*\[((?:\s*(?:"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')\s*,?)*)\s*\n\s*]""",
    )

    private fun extractClarify(t: String): List<String> {
        val m = Regex("""CLARIFY\w*\s*=\s*\[((?:\s*(?:"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')\s*,?)*)\s*\n\s*]""")
            .find(t) ?: return emptyList()
        return quotedParts(m.groupValues[1]).filter { it.isNotBlank() }
    }

    private fun extractPacks(t: String): List<ReplyPack> {
        val out = mutableListOf<ReplyPack>()
        strictList.findAll(t).forEach { m ->
            val name = m.groupValues[1]
            val items = quotedParts(m.groupValues[2]).filter { it.isNotBlank() }
            if (items.size < 2) return@forEach // списки настроек вида [1, 2] не трогаем
            // служебные списки кода — не наборы ответов
            if (name.lowercase() in serviceListNames) return@forEach
            val label = knownPacks[name] ?: humanize(name)
            when {
                label == "CLARIFY" -> return@forEach // уточняющие — отдельно
                else -> out.add(ReplyPack(id = canonicalPackId(name, label), name = label, items = items.take(300)))
            }
        }
        return out
    }

    /**
     * Расписание с учётом python-условий: события до «wd < 5» — ежедневные,
     * внутри этого блока — будни, в ветке «wd == 4» — только пятница.
     */
    private fun extractScheduleZoned(t: String): List<ScheduleEvent> {
        val wdAny = Regex("wd\\s*<\\s*5").find(t)?.range?.first
        val wdFri = Regex("wd\\s*==\\s*4").find(t)?.range?.first
        // «else:» после пятничной ветки = «все дни, кроме пятницы» (1–4)
        val wdElse = wdFri?.let {
            Regex("\\n\\s*else\\s*:").find(t, it)?.range?.first
        }
        val out = mutableListOf<ScheduleEvent>()
        val tuple = Regex("""\(\s*(\d{1,2})\s*,\s*(\d{1,2})\s*,\s*"([^"]+)["\s)]""")
        tuple.findAll(t).forEach { m ->
            val h = m.groupValues[1].toIntOrNull() ?: return@forEach
            val min = m.groupValues[2].toIntOrNull() ?: return@forEach
            if (h !in 0..23 || min !in 0..59) return@forEach
            val pos = m.range.first
            val days = when {
                wdAny != null && pos > wdAny &&
                    (wdFri == null || pos < wdFri) -> listOf(1, 2, 3, 4, 5) // будни
                wdFri != null && (wdElse == null || pos < wdElse) && pos > wdFri ->
                    listOf(5) // пятничный блок
                wdElse != null && pos > wdElse -> listOf(1, 2, 3, 4) // кроме пятницы
                else -> listOf(1, 2, 3, 4, 5, 6, 7)
            }
            var text = unescape(m.groupValues[3])
            // «»канал …» — событие публикуется в канал публикаций, не в чат.
            var toChannel = false
            if (text.startsWith("»канал")) {
                toChannel = true
                text = text.removePrefix("»канал").trim()
            }
            out.add(
                ScheduleEvent(
                    id = "imp_${h}_${min}_${out.size}",
                    hour = h, minute = min, text = text, days = days,
                    menu = if (text.uppercase().contains("ПЕРЕКУР"))
                        PerkurPresets.smokeMenu() else emptyList(),
                    toChannel = toChannel,
                ))
        }
        return out.distinctBy { it.days.joinToString() + it.hour * 60 + it.minute }
    }

    /** Имена, которые в коде почти всегда служебные, а не наборы ответов. */
    private val serviceListNames = setOf(
        "schedule", "content_types", "commands", "messages", "history",
        "days", "buttons", "menu", "items", "payload", "choices", "keyboards",
    )

    /** Известные наборы получают стабильные id — inline-кнопки перекура
     *  ссылаются именно на них, поэтому «Набор пуст» не возникает. */
    private fun canonicalPackId(name: String, label: String): String = when (label) {
        "😂 Шутки" -> "pack_jokes"
        "🚬 Сарказм: покурил" -> "pack_smoke_done"
        "💪 Сарказм: остался здоровым" -> "pack_smoke_healthy"
        else -> "imp_$name"
    }

    private fun humanize(constName: String): String =
        constName.lowercase().split('_')
            .filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            .let { "📦 $it" }
}
