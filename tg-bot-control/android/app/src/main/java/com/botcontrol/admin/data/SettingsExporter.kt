package com.botcontrol.admin.data

import com.botcontrol.admin.data.local.BotRuleEntity

/**
 * Экспорт настроек бота в исходник на Python — ровно в том формате, который
 * читает [ScriptImporter] (тот же файл можно потом импортировать обратно):
 * команды и правила с inline-кнопками И обработчиком нажатий (кнопки после
 * повторного импорта работают), анимации, клавиатура чата, наборы ответов,
 * уточняющие вопросы, расписание с днями недели. Блок ИИ — только если ИИ
 * у бота включён (ИИ — отдельная фича, по умолчанию выключен).
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
        // Каждая строка, кроме последней, — с "\n": раньше переносы терялись,
        // и после импорта /help превращался в одну длинную строку.
        return "(\n" + lines.mapIndexed { i, l ->
            "        " + py(if (i < lines.size - 1) l + "\n" else l)
        }.joinToString("\n") + "\n    )"
    }

    /** Константы наборов: id набора → уникальное имя (одно на весь файл). */
    private fun packConsts(packs: List<ReplyPack>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val used = HashSet<String>()
        packs.forEach { p ->
            var name = packConst(p)
            var i = 2
            while (!used.add(name)) name = packConst(p) + "_" + i++
            out[p.id] = name
        }
        return out
    }

    private fun animConst(owner: String): String =
        "ANIM_" + owner.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').uppercase().ifBlank { "X" }

    /**
     * Вызов помощника анимации: шаблон строкой или константа своих кадров.
     * Формат читает [ScriptImporter] (animFromCall).
     */
    private fun animCall(spec: AnimSpec, chat: String, framesConst: String?, consts: Map<String, String>,
                         extra: String): String {
        val args = ArrayList<String>()
        args.add(chat)
        // Кадры — всегда готовым списком (файл работает и как обычный бот);
        // preset= подсказывает BotControl, что это шаблон, и импорт вернёт его.
        args.add(framesConst ?: "[]")
        args.add("%.1f".format(java.util.Locale.US, Anim.interval(spec) / 1000.0))
        if (spec.finalText.isNotBlank()) args.add(py(spec.finalText))
        if (spec.preset != "custom") args.add("preset=${py(spec.preset)}")
        if (spec.preset != "custom" && spec.text.isNotBlank()) args.add("text=${py(spec.text)}")
        if (spec.loops > 1) args.add("loops=${spec.loops}")
        if (spec.preset == "custom" && spec.mono) args.add("mono=True")
        consts[spec.packId]?.let { args.add("pack=$it") }
        return "play_animation(${args.joinToString(", ")}$extra)"
    }

    /** Все inline-меню бота: какие есть у правил и у событий расписания. */
    /**
     * id наборов, на которые ссылается бот: правила «набор», анимации
     * (финал слот-машины из набора), inline-кнопки правил и событий.
     * Нужен экспорту (в .py — только свои наборы) и импорту (не трогать
     * наборы, которыми пользуются другие боты).
     */
    fun packRefs(rules: List<BotRuleEntity>, events: List<ScheduleEvent>): Set<String> {
        val out = HashSet<String>()
        fun btn(b: InlineBtn) {
            if (b.packId.isNotBlank()) out.add(b.packId)
            if (b.action == "anim" && b.script.isNotBlank()) {
                Anim.decode(b.script).packId.takeIf { it.isNotBlank() }?.let(out::add)
            }
        }
        rules.forEach { r ->
            if (r.packId.isNotBlank()) out.add(r.packId)
            if (r.actionType == "anim" && r.script.isNotBlank()) {
                Anim.decode(r.script).packId.takeIf { it.isNotBlank() }?.let(out::add)
            }
            BotJson.menu(r.menu).forEach(::btn)
        }
        events.forEach { e -> e.menu.forEach(::btn) }
        return out
    }

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

        // ---------- ИИ (только если включён: ИИ — отдельная фича) ----------
        val aiOn = store.llmEnabled(botId)
        val prompt = store.systemPrompt(botId)
        if (aiOn) {
            sb.appendLine("# ---------- ИИ: отвечает только на /chat вопрос и правила «ИИ» ----------")
            if (prompt.isNotBlank()) {
                val pl = prompt.lines()
                sb.appendLine("SYSTEM_PROMPT = (")
                pl.forEachIndexed { i, line -> sb.appendLine("    ${py(if (i < pl.size - 1) line + "\n" else line)}") }
                sb.appendLine(")")
                sb.appendLine()
            }
            sb.appendLine("LLM_MAX_TOKENS = ${store.aiMaxTokens(botId)}")
            sb.appendLine("LLM_MAX_TOP_K = ${store.aiTopK(botId)}")
            sb.appendLine("payload = {")
            sb.appendLine("    \"temperature\": ${store.aiTemperature(botId)},")
            sb.appendLine("    \"max_tokens\": LLM_MAX_TOKENS,")
            sb.appendLine("}")
            sb.appendLine("MAX_HISTORY = ${store.historyLimit(botId)}")
            sb.appendLine()
        }
        sb.appendLine("ANSWER_COOLDOWN = ${store.cooldownSec(botId)}")
        sb.appendLine("DEFAULT_TYPING_SECONDS = ${store.typingSeconds(botId)}")
        sb.appendLine()

        val rules = repository.botRules(botId).filter { it.enabled }
        val events = store.schedule(botId).filter { it.enabled }
        val menus = collectMenus(rules, events)
        // Только наборы ЭТОГО бота: на которые ссылаются его правила/кнопки/
        // анимации/расписание или импортированные для него. Раньше в .py
        // попадала вся библиотека — тексты других ботов.
        val refs = packRefs(rules, events)
        val foreignRefs = HashSet<String>()
        for (p in store.profiles()) {
            if (p.id != botId) foreignRefs += packRefs(repository.botRules(p.id), store.schedule(p.id))
        }
        val packs = store.packs().filter {
            it.items.isNotEmpty() && (
                it.ownerBotId == botId || it.id in refs ||
                    // общий набор библиотеки, которым не пользуются другие боты
                    (it.ownerBotId == 0L && it.id !in foreignRefs))
        }
        val consts = packConsts(packs)

        // ---------- анимации: свои кадры → константы + помощник ----------
        val animFrames = LinkedHashMap<String, List<String>>() // константа → кадры
        val animConstOf = HashMap<String, String>()             // encode(spec) → константа
        fun registerAnim(spec: AnimSpec, owner: String) {
            val key = Anim.encode(spec)
            if (key in animConstOf) return
            // шаблон → его кадры одним проходом (повторы делает loops=)
            val frames = if (spec.preset == "custom") spec.frames
            else Anim.frames(spec.copy(loops = 1), "{user}", kotlin.random.Random(7))
            if (frames.isEmpty()) return
            var name = animConst(owner)
            var i = 2
            while (name in animFrames) name = animConst(owner) + "_" + i++
            animFrames[name] = frames
            animConstOf[key] = name
        }
        var usesAnim = false
        rules.filter { it.actionType == "anim" }.forEach {
            usesAnim = true; registerAnim(Anim.decode(it.script), it.pattern)
        }
        menus.flatMap { it.second }.filter { it.action == "anim" }.forEach {
            usesAnim = true; registerAnim(Anim.decode(it.script), it.id.ifBlank { it.label })
        }
        if (usesAnim) {
            sb.appendLine("# ---------- анимация: одно сообщение правится кадр за кадром ----------")
            listOf(
                "def play_animation(chat_id, frames, delay=0.7, final=None, reply_markup=None,",
                "                   message_id=None, loops=1, pack=None, **kw):",
                "    \"\"\"Одно сообщение правится кадр за кадром. preset=/text=/mono= читает BotControl.",
                "    Кадр «photo: ссылка\\nподпись» — картинка (editMessageMedia), без photo: — только подпись.\"\"\"",
                "    frames = list(frames) * max(1, loops)",
                "    if pack:",
                "        final = random.choice(pack)",
                "    photos = [f.partition(\"\\n\")[0][6:].strip() for f in frames if f.startswith(\"photo:\")]",
                "    if photos:",
                "        def caption(f):",
                "            return f.partition(\"\\n\")[2] if f.startswith(\"photo:\") else f",
                "        cur = photos[0]",
                "        m_id = message_id",
                "        if m_id is None:",
                "            m_id = bot.send_photo(chat_id, cur, caption=caption(frames[0])).message_id",
                "            frames = frames[1:]",
                "        for frame in frames:",
                "            time.sleep(delay)",
                "            src = frame.partition(\"\\n\")[0][6:].strip() if frame.startswith(\"photo:\") else cur",
                "            if src != cur:",
                "                bot.edit_message_media(InputMediaPhoto(src, caption=caption(frame)), chat_id, m_id)",
                "                cur = src",
                "            else:",
                "                bot.edit_message_caption(caption(frame), chat_id, m_id)",
                "        if final:",
                "            time.sleep(delay)",
                "            bot.edit_message_caption(final, chat_id, m_id, reply_markup=reply_markup)",
                "        return",
                "    m_id = message_id",
                "    if m_id is None:",
                "        m_id = bot.send_message(chat_id, frames[0]).message_id",
                "        frames = frames[1:]",
                "    for frame in frames:",
                "        time.sleep(delay)",
                "        try:",
                "            bot.edit_message_text(frame, chat_id, m_id)",
                "        except Exception:",
                "            pass  # «message is not modified» и т.п.",
                "    if final:",
                "        time.sleep(delay)",
                "        bot.edit_message_text(final, chat_id, m_id, reply_markup=reply_markup)",
            ).forEach { sb.appendLine(it) }
            sb.appendLine()
            sb.appendLine()
            animFrames.forEach { (name, frames) ->
                if (frames.any { f -> Anim.photoSrc(f)?.let { Anim.isLocalSrc(it) } == true }) {
                    sb.appendLine("# ⚠️ в кадрах — файлы с телефона: в другом месте замени их ссылками https://")
                }
                sb.appendLine("$name = [")
                frames.forEach { sb.appendLine("    ${py(it)},") }
                sb.appendLine("]")
                sb.appendLine()
            }
        }
        val ctx = Ctx(menus, consts, animConstOf)

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
        rules.filter { it.type == "command" }.forEach { rule -> emitRule(sb, rule, ctx) }
        rules.filter { it.type != "command" }.forEach { rule -> emitRule(sb, rule, ctx) }

        // ---------- обработчик нажатий: без него кнопки после импорта «немые» ----------
        emitCallbacks(sb, menus, ctx)

        // ---------- inline-меню ----------
        menus.forEach { (name, menu) ->
            sb.appendLine("def $name():")
            menuBody(menu).forEach { sb.appendLine(it) }
            sb.appendLine()
            sb.appendLine()
        }

        // ---------- наборы ответов ----------
        packs.forEach { pack ->
            // «# Название» на строке объявления — импорт вернёт название набора
            val title = pack.name.replace(Regex("[\r\n]+"), " ").trim()
            sb.appendLine("${consts[pack.id]} = [" + if (title.isNotBlank()) "  # $title" else "")
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
            if (weekdays.isNotEmpty() || friday.isNotEmpty() || monThu.isNotEmpty()) {
                sb.appendLine("    if wd < 5:")
                sb.appendLine("        schedule.extend([")
                weekdays.forEach { sb.appendLine("            ${tuple(it)}") }
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
                    sb.appendLine("        if wd != 4:")
                    sb.appendLine("            schedule.extend([")
                    monThu.forEach { sb.appendLine("                ${tuple(it)}") }
                    sb.appendLine("            ])")
                }
            }
            // Свои наборы дней (выходные, Пн+Ср, …): отдельный блок на набор,
            // иначе после импорта они молча становились буднями.
            other.groupBy { it.days.sorted() }.forEach { (days, list) ->
                val wds = days.map { it - 1 }
                val cond = when {
                    wds == listOf(5, 6) -> "wd >= 5"
                    wds.size == 1 -> "wd == ${wds[0]}"
                    else -> "wd in (${wds.joinToString(", ")})"
                }
                sb.appendLine("    if $cond:")
                sb.appendLine("        schedule.extend([")
                list.forEach { sb.appendLine("            ${tuple(it)}") }
                sb.appendLine("        ])")
            }
            sb.appendLine("    return sorted(schedule, key=lambda item: (item[0], item[1]))")
            sb.appendLine()
            sb.appendLine()
        }

        return sb.toString()
    }

    private class Ctx(
        val menus: List<Pair<String, List<InlineBtn>>>,
        val consts: Map<String, String>,
        val animConstOf: Map<String, String>,
    )

    private fun packExpr(packId: String, ctx: Ctx): String =
        ctx.consts[packId]?.let { "random.choice($it)" } ?: py("(набор $packId не найден)")

    /**
     * @bot.callback_query_handler — по ветке на каждую кнопку с действием.
     * Формат совпадает с тем, что понимает [ScriptImporter]: всплывашка
     * (answer_callback_query), флаг reminders_running = True/False,
     * random.choice(НАБОР), edit_message_text / send_message, play_animation.
     */
    private fun emitCallbacks(sb: StringBuilder, menus: List<Pair<String, List<InlineBtn>>>, ctx: Ctx) {
        val seen = HashSet<String>()
        val btns = menus.flatMap { it.second }.filter { !it.isUrl && it.action != "url" }
            .filter { seen.add(it.id.ifBlank { it.label }) }
        if (btns.isEmpty()) return
        sb.appendLine("reminders_running = False")
        sb.appendLine()
        sb.appendLine()
        sb.appendLine("@bot.callback_query_handler(func=lambda call: True)")
        sb.appendLine("def on_callback(call):")
        sb.appendLine("    global reminders_running")
        sb.appendLine("    chat_id = call.message.chat.id")
        btns.forEachIndexed { i, b ->
            val id = b.id.ifBlank { b.label }
            sb.appendLine("    ${if (i == 0) "if" else "elif"} call.data == ${py(id)}:")
            val mid = "call.message.message_id"
            fun toast(t: String) { if (t.isNotBlank()) sb.appendLine("        bot.answer_callback_query(call.id, ${py(t)})") }
            when (b.action) {
                "reminders_on", "reminders_off" -> {
                    val on = b.action == "reminders_on"
                    sb.appendLine("        if ${if (on) "" else "not "}reminders_running:")
                    sb.appendLine("            bot.answer_callback_query(call.id, ${py(b.toastNoChange.ifBlank { if (on) "Уже работает" else "Уже остановлено" })})")
                    sb.appendLine("            return")
                    sb.appendLine("        reminders_running = ${if (on) "True" else "False"}")
                    toast(b.toast)
                    val t = b.text.ifBlank { if (on) "▶️ Напоминания включены." else "⏹ Напоминания выключены." }
                    sb.appendLine("        bot.edit_message_text(${textExpression(t)}, chat_id, $mid)")
                }
                "pack" -> {
                    toast(b.toast)
                    if (b.edit) sb.appendLine("        bot.edit_message_text(${packExpr(b.packId, ctx)}, chat_id, $mid)")
                    else sb.appendLine("        bot.send_message(chat_id, ${packExpr(b.packId, ctx)})")
                }
                "script" -> {
                    toast(b.toast)
                    sb.appendLine("        bot.send_message(chat_id, run_script(${py(b.script.take(4000))}))")
                }
                "anim" -> {
                    toast(b.toast)
                    val spec = Anim.decode(b.script)
                    sb.appendLine("        " + animCall(spec, "chat_id", ctx.animConstOf[Anim.encode(spec)], ctx.consts,
                        if (b.edit) ", message_id=$mid, reply_markup=call.message.reply_markup" else ""))
                }
                "dice" -> {
                    toast(b.toast)
                    sb.appendLine("        bot.send_dice(chat_id, emoji=${py(b.text.ifBlank { "🎲" })})")
                }
                else -> {
                    toast(b.toast)
                    val t = textExpression(b.text.ifBlank { b.label })
                    if (b.edit) sb.appendLine("        bot.edit_message_text($t, chat_id, $mid)")
                    else sb.appendLine("        bot.send_message(chat_id, $t)")
                }
            }
        }
        sb.appendLine()
        sb.appendLine()
    }

    private fun emitRule(sb: StringBuilder, rule: BotRuleEntity, ctx: Ctx) {
        ruleHeader(rule).forEach { sb.appendLine(it) }
        val menuName = ctx.menus.firstOrNull { (_, m) ->
            m.isNotEmpty() && m.map { it.id }.toSet() == BotJson.menu(rule.menu).map { it.id }.toSet()
        }?.first
        val kbArg = if (menuName != null) ", reply_markup=$menuName()" else ""
        when (rule.actionType) {
            "pack" -> sb.appendLine("    bot.send_message(msg.chat.id, ${packExpr(rule.packId, ctx)}$kbArg)")
            "anim" -> {
                val spec = Anim.decode(rule.script)
                sb.appendLine("    " + animCall(spec, "msg.chat.id", ctx.animConstOf[Anim.encode(spec)], ctx.consts, kbArg))
            }
            "dice" -> sb.appendLine("    bot.send_dice(msg.chat.id, emoji=${py(rule.responseText.trim().ifBlank { "🎲" })})")
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
