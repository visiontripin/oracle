package com.botcontrol.admin.data

import com.botcontrol.admin.data.local.BotRuleEntity

/**
 * Экспорт настроек бота в исходник на Python — ровно в том формате, который
 * читает [ScriptImporter] (тот же файл можно потом импортировать обратно):
 * характер и параметры ИИ, команды и правила с inline-кнопками, клавиатура
 * чата, наборы ответов, уточняющие вопросы, расписание с днями недели.
 *
 * Токен в экспорт НЕ попадает — только плейсхолдер.
 */
object SettingsExporter {

    private fun py(s: String): String {
        val escaped = s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("$", "\\$")
        return "\"$escaped\""
    }

    /** Имя константы-набора из его названия (латиница, ЗАГЛАВНЫЕ). */
    private fun packConst(pack: ReplyPack): String = when {
        pack.name.contains("Шутки") -> "JOKES"
        pack.name.contains("покурил", ignoreCase = true) -> "SMOKE_DONE_SARCASM"
        pack.name.contains("здоров", ignoreCase = true) -> "SMOKE_HEALTHY_SARCASM"
        else -> {
            val latin = pack.name.replace(Regex("[^A-Za-z0-9]+"), "_")
                .trim('_').uppercase()
            if (latin.isNotBlank() && latin.first().isLetter()) "$latin"
            else "PACK_CUSTOM_${pack.id.replace("-", "_").uppercase()}"
        }
    }

    /** Текст правила одним выражением: строка, склейка строк или набор. */
    private fun textExpression(text: String): String {
        if (text.isBlank()) return "\"\""
        val lines = text.lineSequence().toList()
        if (lines.size <= 1) return py(text)
        return "( " + lines.joinToString(" ") { py(it) } + " )"
    }

    /** Все inline-меню бота: какие есть у правил и у событий расписания. */
    private fun collectMenus(
        rules: List<BotRuleEntity>,
        events: List<ScheduleEvent>,
    ): List<Pair<String, List<InlineBtn>>> {
        val out = ArrayList<Pair<String, List<InlineBtn>>>()
        val seen = HashSet<String>()
        fun add(name: String, menu: List<InlineBtn>) {
            if (menu.isEmpty()) return
            val key = menu.joinToString("|") { it.label + it.action + it.packId + it.text + it.url + it.row }
            if (!seen.add(key)) return
            out.add(name to menu)
        }
        rules.forEach { r ->
            val menu = BotJson.menu(r.menu)
            if (menu.isNotEmpty()) {
                val name = "kb_" + r.pattern.removePrefix("/")
                    .replace(Regex("[^A-Za-z0-9_]"), "_").lowercase().trim('_')
                    .ifBlank { "rule" }
                add(name, menu)
            }
        }
        events.forEach { e ->
            if (e.menu.isNotEmpty()) {
                add("kb_${e.hour}_${e.minute}", e.menu)
            }
        }
        return out
    }

    /** Тело функции меню: по ряду на каждую строку markup.row(…). */
    private fun menuBody(menu: List<InlineBtn>): List<String> {
        val rows = menu.layoutRows()
        val lines = ArrayList<String>()
        lines.add("    markup = InlineKeyboardMarkup()")
        rows.forEach { row ->
            val btns = row.joinToString(", ") { b ->
                if (b.url.isNotBlank()) "InlineKeyboardButton(${py(b.label)}, url=${py(b.url)})"
                else "InlineKeyboardButton(${py(b.label)}, callback_data=${py(b.id.ifBlank { b.label })})"
            }
            lines.add("    markup.row($btns)")
        }
        lines.add("    return markup")
        return lines
    }

    /** Декоратор и заголовок правила в зависимости от его типа. */
    private fun ruleHeader(rule: BotRuleEntity): List<String> = when (rule.type) {
        "command" -> listOf(
            "@bot.message_handler(commands=[\"${rule.pattern.removePrefix("/")}\"])",
            "def cmd_${rule.pattern.removePrefix("/").replace(Regex("[^A-Za-z0-9_]"), "_")}(msg):",
        )
        "button" -> listOf(
            "@bot.message_handler(func=lambda m: m.text == ${py(rule.pattern)})",
            "def btn_${slug(rule.pattern)}(msg):",
        )
        else -> listOf(
            "@bot.message_handler(func=lambda m: ${py(rule.pattern)} in (m.text or \"\").lower())",
            "def on_${slug(rule.pattern)}(msg):",
        )
    }

    private fun slug(s: String): String =
        s.replace(Regex("[^A-Za-z0-9_]+"), "_").lowercase().trim('_').ifBlank { "rule" }

    suspend fun build(
        store: LocalBotStore,
        repository: com.botcontrol.admin.data.BotRepository,
        botId: Long,
    ): String {
        val sb = StringBuilder()

        sb.appendLine("# =============================================================")
        sb.appendLine("# Экспорт настроек BotControl — бот ID $botId")
        sb.appendLine("# Файл можно править и импортировать обратно:")
        sb.appendLine("# Скрипты → Импорт настроек из кода → Выбрать файл / вставить.")
        sb.appendLine("# ТОКЕН сюда не входит (секрет) — укажи свой при необходимости.")
        sb.appendLine("# =============================================================")
        sb.appendLine()
        sb.appendLine("BOT_TOKEN = \"ВСТАВЬ_ТОКЕН_ОТСЮДА_НЕ_ЭКСПОРТИРУЕТСЯ\"")
        sb.appendLine()

        // ---------- ИИ ----------
        val prompt = store.systemPrompt(botId)
        if (prompt.isNotBlank()) {
            sb.appendLine("SYSTEM_PROMPT = (")
            prompt.lineSequence().forEach { line -> sb.appendLine("    ${py(line)}") }
            sb.appendLine(")")
            sb.appendLine()
        }
        val temp = store.aiTemperature(botId)
        val topK = store.aiTopK(botId)
        val maxTokens = store.aiMaxTokens(botId)
        sb.appendLine("LLM_MAX_TOKENS = $maxTokens")
        sb.appendLine("LLM_MAX_TOP_K = $topK")
        sb.appendLine("payload = {")
        sb.appendLine("    \"temperature\": $temp,")
        sb.appendLine("    \"max_tokens\": LLM_MAX_TOKENS,")
        sb.appendLine("}")
        sb.appendLine()
        sb.appendLine("MAX_HISTORY = ${store.historyLimit(botId)}")
        sb.appendLine("ANSWER_COOLDOWN = ${store.cooldownSec(botId)}")
        sb.appendLine("DEFAULT_TYPING_SECONDS = ${store.typingSeconds(botId)}")
        sb.appendLine()

        val rules = repository.botRules(botId).filter { it.enabled }
        val events = store.schedule(botId).filter { it.enabled }
        val menus = collectMenus(rules, events)

        // ---------- клавиатура чата ----------
        val keyboard = store.keyboard(botId)
        if (keyboard.isNotEmpty()) {
            sb.appendLine("def chat_keyboard():")
            sb.appendLine("    markup = ReplyKeyboardMarkup(resize_keyboard=True)")
            keyboard.chunked(2).forEach { row ->
                sb.appendLine("    markup.row(" + row.joinToString(", ") { "KeyboardButton(${py(it)})" } + ")")
            }
            sb.appendLine("    return markup")
            sb.appendLine()
            sb.appendLine()
        }

        // ---------- правила: команды, кнопки клавиатуры, фразы ----------
        rules.filter { it.type == "command" }.forEach { rule -> emitRule(sb, rule, menus) }
        rules.filter { it.type != "command" }.forEach { rule -> emitRule(sb, rule, menus) }

        // ---------- inline-меню ----------
        menus.forEach { (name, menu) ->
            sb.appendLine("def $name():")
            menuBody(menu).forEach { sb.appendLine(it) }
            sb.appendLine()
            sb.appendLine()
        }

        // ---------- наборы ответов ----------
        val packs = store.packs().filter { it.items.isNotEmpty() }
        packs.forEach { pack ->
            sb.appendLine("${packConst(pack)} = [")
            pack.items.take(300).forEach { item -> sb.appendLine("    ${py(item)},") }
            sb.appendLine("]")
            sb.appendLine()
        }

        // ---------- уточняющие вопросы ----------
        val clarify = store.clarifyQuestions(botId)
        if (clarify.isNotEmpty()) {
            sb.appendLine("CLARIFY_QUESTIONS = [")
            clarify.forEach { q -> sb.appendLine("    ${py(q)},") }
            sb.appendLine("]")
            sb.appendLine()
        }

        // ---------- расписание (зоны: ежедневные / будни / пятница) ----------
        if (events.isNotEmpty()) {
            fun tuple(e: ScheduleEvent): String {
                val menuName = menus.firstOrNull { (_, m) ->
                    m.map { it.id }.toSet() == e.menu.map { it.id }.toSet() && m.isNotEmpty()
                }?.first
                val text = (if (e.toChannel) "»канал " else "") + e.text
                val tail = if (menuName != null) ", $menuName()" else ""
                return "(${e.hour}, ${e.minute}, ${py(text)}$tail),"
            }

            val daily = events.filter { it.days.size >= 7 }
            val friday = events.filter { it.days == listOf(5) }
            val monThu = events.filter { it.days == listOf(1, 2, 3, 4) }
            val weekdays = events.filter { it.days == listOf(1, 2, 3, 4, 5) }
            val other = events.filter {
                it.days.size != 7 && it.days != listOf(5) && it.days != listOf(1, 2, 3, 4) &&
                    it.days != listOf(1, 2, 3, 4, 5)
            }

            sb.appendLine("def get_today_schedule(now):")
            sb.appendLine("    wd = now.weekday()")
            sb.appendLine("    schedule = [")
            daily.forEach { sb.appendLine("        ${tuple(it)}") }
            sb.appendLine("    ]")
            if (weekdays.isNotEmpty() || other.isNotEmpty() || friday.isNotEmpty() ||
                monThu.isNotEmpty()) {
                sb.appendLine("    if wd < 5:")
                sb.appendLine("        schedule.extend([")
                weekdays.forEach { sb.appendLine("            ${tuple(it)}") }
                other.forEach { sb.appendLine("            ${tuple(it)}  # свой набор дней") }
                sb.appendLine("        ])")
                if (friday.isNotEmpty()) {
                    sb.appendLine("        if wd == 4:")
                    friday.forEach { sb.appendLine("            schedule.append(${tuple(it)})") }
                    if (monThu.isNotEmpty()) {
                        sb.appendLine("        else:")
                        sb.appendLine("            schedule.extend([")
                        monThu.forEach { sb.appendLine("                ${tuple(it)}") }
                        sb.appendLine("            ])")
                    }
                } else if (monThu.isNotEmpty()) {
                    sb.appendLine("        else:")
                    sb.appendLine("            schedule.extend([")
                    monThu.forEach { sb.appendLine("                ${tuple(it)}") }
                    sb.appendLine("            ])")
                }
            }
            sb.appendLine("    return sorted(schedule, key=lambda item: (item[0], item[1]))")
            sb.appendLine()
            sb.appendLine()
        }

        return sb.toString()
    }

    private fun emitRule(sb: StringBuilder, rule: BotRuleEntity, menus: List<Pair<String, List<InlineBtn>>>) {
        ruleHeader(rule).forEach { sb.appendLine(it) }
        val menuName = menus.firstOrNull { (_, m) ->
            m.isNotEmpty() && m.map { it.id }.toSet() == BotJson.menu(rule.menu).map { it.id }.toSet()
        }?.first
        val kbArg = if (menuName != null) ", reply_markup=$menuName()" else ""
        when (rule.actionType) {
            "pack" -> {
                val pack = runCatching {
                    // имя набора для экспорта: берём константу из списка
                    rule.packId.removePrefix("pack_")
                }.getOrDefault(rule.packId)
                sb.appendLine("    bot.send_message(msg.chat.id, random.choice(${
                    Regex("^[A-Za-z_][A-Za-z0-9_]*$").let { re ->
                        if (re.matches(pack)) pack.uppercase() else pack
                    }
                })$kbArg)")
            }
            "llm" -> sb.appendLine("    bot.send_message(msg.chat.id, ask_llm(msg.chat.id, msg.text)$kbArg)")
            "script" -> sb.appendLine("    bot.send_message(msg.chat.id, run_script(${
                py(rule.script.take(4000))
            })$kbArg)")
            else -> sb.appendLine("    bot.send_message(msg.chat.id, ${textExpression(rule.responseText)}$kbArg)")
        }
        sb.appendLine()
        sb.appendLine()
    }
}
