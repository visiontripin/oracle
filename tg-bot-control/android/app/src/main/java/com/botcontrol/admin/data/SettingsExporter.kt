package com.botcontrol.admin.data

/**
 * Экспорт настроек бота в исходник на Python (тот же формат, что читает
 * ScriptImporter). Экспортируются ТЕКУЩИЕ (изменённые) настройки:
 * характер ИИ, параметры генерации, память, паузы, команды меню,
 * приветствие /start, наборы ответов, расписание с днями недели.
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
            prompt.lineSequence().forEach { line ->
                sb.appendLine("    ${py(line)}")
            }
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

        // ---------- главное меню ----------
        val menu = store.menuCommands(botId)
        if (menu.isNotEmpty()) {
            val items = menu.joinToString(", ") { "\"${it.command}\"" }
            sb.appendLine("menu_commands = [$items]")
            sb.appendLine()
        }

        // ---------- правила: приветствие /start, /help, текстовые ----------
        val rules = repository.botRules(botId).filter { it.enabled }
        val startRule = rules.firstOrNull {
            it.type == "command" && it.pattern.removePrefix("/").equals("start", true)
        }
        if (startRule != null && startRule.responseText.isNotBlank()) {
            sb.appendLine("@bot.message_handler(commands=[\"start\"])")
            sb.appendLine("def cmd_start(msg):")
            sb.appendLine("    bot.send_message(msg.chat.id, ${py(startRule.responseText)}, reply_markup=start_keyboard())")
            sb.appendLine()
            sb.appendLine()
        }
        val helpRule = rules.firstOrNull {
            it.type == "command" && it.pattern.removePrefix("/").equals("help", true)
        }
        if (helpRule != null && helpRule.responseText.isNotBlank()) {
            sb.appendLine("@bot.message_handler(commands=[\"help\"])")
            sb.appendLine("def cmd_help(msg):")
            sb.appendLine("    bot.send_message(msg.chat.id, ${py(helpRule.responseText)})")
            sb.appendLine()
            sb.appendLine()
        }

        // ---------- кнопки Запуск/Стоп ----------
        sb.appendLine("def start_keyboard():")
        sb.appendLine("    return InlineKeyboardMarkup().row(")
        sb.appendLine("        InlineKeyboardButton(\"▶️ Запуск\", callback_data=\"start\"),")
        sb.appendLine("        InlineKeyboardButton(\"⏹ Стоп\", callback_data=\"stop\"),")
        sb.appendLine("    )")
        sb.appendLine()
        sb.appendLine()

        // ---------- наборы ответов ----------
        val packs = store.packs().filter { it.items.isNotEmpty() }
        packs.forEach { pack ->
            val constName = when {
                pack.name.contains("Шутки") -> "JOKES"
                pack.name.contains("покурил", ignoreCase = true) -> "SMOKE_DONE_SARCASM"
                pack.name.contains("здоров", ignoreCase = true) -> "SMOKE_HEALTHY_SARCASM"
                else -> {
                    val latin = pack.name.replace(Regex("[^A-Za-z0-9]+"), "_")
                        .trim('_').uppercase()
                    if (latin.isNotBlank() && latin.first().isLetter()) "PACK_$latin"
                    else "PACK_CUSTOM_${pack.id.replace("-", "_").uppercase()}"
                }
            }
            sb.appendLine("$constName = [")
            pack.items.take(300).forEach { item ->
                sb.appendLine("    ${py(item)},")
            }
            sb.appendLine("]")
            sb.appendLine()
        }

        // ---------- уточняющие вопросы ----------
        val clarify = store.clarifyQuestions(botId)
        if (clarify.isNotEmpty()) {
            sb.appendLine("CLARIFY_QUESTIONS = [")
            clarify.forEach { q ->
                sb.appendLine("    ${py(q)},")
            }
            sb.appendLine("]")
            sb.appendLine()
        }

        // ---------- расписание (зоны: ежедневные / будни / пятница) ----------
        val events = store.schedule(botId).filter { it.enabled }
        if (events.isNotEmpty()) {
            fun tuple(e: ScheduleEvent): String =
                "(${e.hour}, ${e.minute}, ${py(e.text)}),"

            val daily = events.filter { it.days.size == 7 }
            val friday = events.filter { it.days == listOf(5) }
            val weekdays = events.filter { it.days == listOf(1, 2, 3, 4, 5) }
            val other = events.filter {
                it.days.size != 7 && it.days != listOf(5) && it.days != listOf(1, 2, 3, 4, 5)
            }

            sb.appendLine("def get_today_schedule(now):")
            sb.appendLine("    wd = now.weekday()")
            sb.appendLine("    schedule = [")
            daily.forEach { sb.appendLine("        ${tuple(it)}") }
            sb.appendLine("    ]")
            if (weekdays.isNotEmpty() || other.isNotEmpty() || friday.isNotEmpty()) {
                sb.appendLine("    if wd < 5:")
                sb.appendLine("        schedule.extend([")
                weekdays.forEach { sb.appendLine("            ${tuple(it)}") }
                other.forEach { sb.appendLine("            ${tuple(it)}  # свой набор дней") }
                sb.appendLine("        ])")
                if (friday.isNotEmpty()) {
                    sb.appendLine("        if wd == 4:")
                    friday.forEach {
                        sb.appendLine("            schedule.append(${tuple(it)})")
                    }
                }
            }
            sb.appendLine("    return sorted(schedule, key=lambda item: (item[0], item[1]))")
            sb.appendLine()
            sb.appendLine()
        }

        // ---------- клавиатура перекура (если есть такие события) ----------
        if (events.any { it.menu.isNotEmpty() }) {
            sb.appendLine("def reminder_keyboard():")
            sb.appendLine("    return InlineKeyboardMarkup().row(")
            sb.appendLine("        InlineKeyboardButton(\"🚬 Покурил\", callback_data=\"smoke_done\"),")
            sb.appendLine("        InlineKeyboardButton(\"💪 Остался здоровым\", callback_data=\"smoke_healthy\"),")
            sb.appendLine("    ).row(InlineKeyboardButton(\"😂 Рандомную шутку\", callback_data=\"smoke_joke\"))")
            sb.appendLine()
        }

        return sb.toString()
    }
}
