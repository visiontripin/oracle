package com.botcontrol.admin.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.botcontrol.admin.data.Anim
import com.botcontrol.admin.data.BotJson
import com.botcontrol.admin.data.BotRepository
import com.botcontrol.admin.data.InlineBtn
import com.botcontrol.admin.data.LocalBotStore
import com.botcontrol.admin.data.ScriptImporter
import com.botcontrol.admin.service.ListingEngine
import com.botcontrol.admin.data.local.BotRuleEntity
import com.botcontrol.admin.data.withIds
import com.botcontrol.admin.llm.DeviceLlm
import kotlinx.coroutines.launch

/**
 * Импорт настроек бота из исходника (.py / .js / .txt).
 *
 * Вставь код или выбери файл → «Разобрать» → приложение покажет найденное
 * по категориям (ИИ, поведение, команды, правила, клавиатура, наборы,
 * расписание, канал) → отметь галочками → «Применить к боту».
 *
 * Никаких встроенных примеров: импортируется ровно то, что есть в коде.
 * Если что-то не удалось понять, приложение честно пишет об этом в
 * «Замечания» — и подсказывает, что поправить руками.
 */
@Composable
fun ImportScreen(
    localStore: LocalBotStore,
    repository: BotRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf("") }
    var parsed by remember { mutableStateOf<ScriptImporter.Parsed?>(null) }
    val checked = remember { mutableStateMapOf<String, Boolean>() }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    fun reparse(text: String) {
        if (text.isBlank()) {
            error = "Сначала вставь текст кода или выбери файл"
            parsed = null
            return
        }
        val p = ScriptImporter.parse(text)
        if (p.isEmpty()) {
            error = "Знакомых параметров не нашлось. Проверь, что в коде есть SYSTEM_PROMPT, " +
                "команды (@bot.message_handler), списки фраз, расписание или кнопки."
            parsed = null
            message = ""
        } else {
            error = ""
            parsed = p
            checked.clear()
            p.sections().forEach { checked[it.key] = true }
            message = "Найдено категорий: ${p.sections().size}"
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader().readText()
                }.orEmpty()
            }.onSuccess { text ->
                source = text
                reparse(text)
                if (parsed != null) message = "Файл прочитан: ${text.length} символов — " +
                    "найдено категорий: ${parsed?.sections()?.size ?: 0}"
            }.onFailure { error = "Не удалось прочитать файл: ${it.message}" }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text("Импорт настроек", style = MaterialTheme.typography.titleLarge)
        }
        Text("Вставь код бота (Python: telebot/aiogram, JavaScript) или код, сгенерированный " +
            "по промту из раздела «🧠 Промт для создания бота». Приложение разберёт характер ИИ, " +
            "команды и правила, кнопки под сообщениями, клавиатуру чата, наборы ответов и расписание.",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            source, { source = it },
            label = { Text("Код бота (Python / JavaScript / TXT)") },
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().height(150.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { reparse(source) }, modifier = Modifier.weight(1f)) {
                Text("🔍 Разобрать")
            }
            OutlinedButton(onClick = { filePicker.launch("*/*") }, modifier = Modifier.weight(1f)) {
                Text("📂 Выбрать файл")
            }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))

        parsed?.let { p ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Найдено — отметь, что применить:",
                        style = MaterialTheme.typography.titleSmall)
                    p.sections().forEach { section ->
                        Row(verticalAlignment = Alignment.Top) {
                            Checkbox(
                                checked = checked[section.key] ?: true,
                                onCheckedChange = { checked[section.key] = it },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(section.title, style = MaterialTheme.typography.bodyMedium)
                                section.details.forEach { line ->
                                    Text("• $line", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Button(onClick = {
                        scope.launch {
                            val botId = resolveBotId(localStore)
                            val result = applyParsed(
                                localStore = localStore,
                                repository = repository,
                                botId = botId,
                                parsed = p,
                                wanted = { key -> checked[key] ?: true },
                            )
                            DeviceLlm.log("📥 Импорт применён: ${result.take(200)}")
                            message = "✅ $result"
                            parsed = null
                            source = ""
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("✅ Применить к боту") }
                }
            }

            if (p.warnings.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Замечания (что поправить после импорта)",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.tertiary)
                        p.warnings.forEach { w ->
                            Text(if (w.startsWith("ℹ️")) w else "⚠️ $w", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { source = ""; parsed = null; message = ""; error = "" },
            modifier = Modifier.fillMaxWidth()) {
            Text("🧹 Очистить")
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ======================================================================
// Применение разобранного конфига
// ======================================================================

private suspend fun resolveBotId(store: LocalBotStore): Long {
    val active = store.activeBotId()
    if (active > 0L) return active
    return store.profiles().firstOrNull()?.id ?: 1L
}

/** Применяет отмеченные категории; возвращает сводку для пользователя. */
private suspend fun applyParsed(
    localStore: LocalBotStore,
    repository: BotRepository,
    botId: Long,
    parsed: ScriptImporter.Parsed,
    wanted: (String) -> Boolean,
): String {
    val done = ArrayList<String>()

    // ---------- наборы ответов (сначала: нужны их id для кнопок) ----------
    val packIdMap = HashMap<String, String>() // imp_XXX -> реальный id набора
    if (parsed.packs.isNotEmpty() && wanted(ScriptImporter.SEC_PACKS)) {
        val current = localStore.packs().toMutableList()
        for (imported in parsed.packs) {
            val existing = current.firstOrNull { it.name == imported.name }
            if (existing != null) {
                current[current.indexOf(existing)] = existing.copy(items = imported.items)
                packIdMap[imported.id] = existing.id
            } else {
                val fresh = imported.copy(id = newPackId(imported.id))
                current.add(fresh)
                packIdMap[imported.id] = fresh.id
            }
        }
        localStore.setPacks(current)
        done.add("наборов: ${parsed.packs.size}")
    }

    fun packId(raw: String): String = packIdMap[raw] ?: raw.removePrefix("imp_")
    /** Набор внутри анимации (слот-машина берёт финал из набора). */
    fun animFixed(script: String): String {
        val spec = Anim.decode(script)
        return if (spec.packId.startsWith("imp_")) Anim.encode(spec.copy(packId = packId(spec.packId))) else script
    }
    fun buttonsFixed(list: List<InlineBtn>): List<InlineBtn> = list.map { b ->
        val withPack = if (b.packId.startsWith("imp_")) b.copy(packId = packId(b.packId)) else b
        if (withPack.action == "anim") withPack.copy(script = animFixed(withPack.script)) else withPack
    }

    // ---------- характер и параметры ИИ ----------
    if (wanted(ScriptImporter.SEC_AI)) {
        parsed.systemPrompt?.let { localStore.setSystemPrompt(it, botId); done.add("характер ИИ") }
        parsed.temperature?.let { localStore.setAiTemperature(it, botId) }
        parsed.maxTokens?.let { localStore.setAiMaxTokens(it, botId) }
        parsed.topK?.let { localStore.setAiTopK(it, botId) }
    }
    if (wanted(ScriptImporter.SEC_BEHAVIOR)) {
        parsed.historyLimit?.let { localStore.setHistoryLimit(it, botId) }
        parsed.cooldownSec?.let { localStore.setCooldownSec(it, botId); done.add("пауза $it с") }
        parsed.typingSec?.let { localStore.setTypingSeconds(it, botId) }
        if (parsed.clarify.isNotEmpty()) {
            localStore.setClarifyQuestions(parsed.clarify, botId)
            localStore.setClarifyEnabled(true, botId)
            done.add("вопросов: ${parsed.clarify.size}")
        }
    }

    // ---------- правила: команды, кнопки клавиатуры, фразы ----------
    val rulesToApply = ArrayList<ScriptImporter.ImportedRule>()
    if (wanted(ScriptImporter.SEC_COMMANDS)) rulesToApply += parsed.commands
    if (wanted(ScriptImporter.SEC_RULES)) rulesToApply += parsed.textRules
    var rulesSaved = 0
    var rulesUpdated = 0
    var rulesDropped = 0
    var ruleButtons = 0
    for (imp in rulesToApply) {
        val menu = buttonsFixed(imp.menu)
        val action = when (imp.actionType) {
            "pack" -> "pack"
            "llm" -> "llm"
            "script" -> "script"
            "anim" -> "anim"
            "dice" -> "dice"
            else -> "text"
        }
        val ruleScript = if (action == "anim") animFixed(imp.script) else imp.script
        val ruleText = when (action) {
            "text" -> imp.text
            "dice" -> imp.text.ifBlank { "🎲" } // эмодзи кубика
            else -> ""
        }
        val packForRule = if (action == "pack") packId(imp.packId) else ""
        if (action == "text" && imp.text.isBlank()) continue // пустой текст — нечего сохранять
        // Самоисцеление: ищем уже сохранённое правило ПО ПАТТЕРНУ, не по типу.
        // Старые версии могли сохранить то же правило иначе (другой тип,
        // другой формат), и тогда новый импорт создавал дубль, а старые
        // кнопки в правиле оставались нерабочими. Теперь обновляем на месте
        // и чистим дубли.
        val existingAll = if (imp.pattern.isBlank()) emptyList()
        else repository.botRules(botId).filter {
            it.pattern.equals(imp.pattern, ignoreCase = true)
        }
        val existing = existingAll.firstOrNull()
        val entity = if (existing != null) {
            existing.copy(
                type = imp.type,
                actionType = action,
                responseText = ruleText,
                packId = packForRule,
                script = ruleScript,
                menu = BotJson.save(menu.withIds()),
            )
        } else {
            BotRuleEntity(
                botId = botId,
                type = imp.type,
                pattern = imp.pattern,
                actionType = action,
                responseText = ruleText,
                packId = packForRule,
                script = ruleScript,
                menu = BotJson.save(menu.withIds()),
            )
        }
        repository.saveBotRule(entity)
        ruleButtons += menu.size
        if (existing == null) {
            rulesSaved++
        } else {
            rulesUpdated++
            existingAll.filter { it.id != entity.id }.forEach { dup ->
                repository.deleteBotRule(dup.id)
                rulesDropped++
            }
        }
    }
    if (rulesSaved + rulesUpdated > 0) {
        done.add("правил: ${rulesSaved + rulesUpdated}" +
            (if (rulesUpdated > 0) " (обновлено $rulesUpdated)" else "") +
            (if (rulesDropped > 0) " (убрано дублей $rulesDropped)" else ""))
    }

    // ---------- меню Telegram (кнопка «Меню») ----------
    if (parsed.menuCommands.isNotEmpty() && wanted(ScriptImporter.SEC_COMMANDS)) {
        localStore.setMenuCommands(parsed.menuCommands, botId)
        done.add("команд в меню: ${parsed.menuCommands.size}")
    }

    // ---------- клавиатура чата ----------
    // Кнопка клавиатуры при нажатии просто отправляет свою надпись текстом.
    // Если правила с таким образцом нет, нажатие падает в запасной ответ —
    // поэтому сразу считаем такие кнопки и показываем их в сводке.
    val kbLabels: List<String> = if (parsed.keyboardRows.isNotEmpty() && wanted(ScriptImporter.SEC_KEYBOARD)) {
        parsed.keyboardRows.flatten().map { it.trim() }.filter { it.isNotBlank() }
    } else emptyList()
    if (kbLabels.isNotEmpty()) {
        localStore.setKeyboard(kbLabels, botId)
        done.add("кнопок клавиатуры: ${kbLabels.size}")
        val rulesNow = repository.botRules(botId)
        // Режим из этого же импорта применяется ниже — берём его сразу,
        // иначе кнопки визарда ложно попадают в «без действия».
        val listingsOn = (if (wanted(ScriptImporter.SEC_CHANNEL)) parsed.listingsOn else null)
            ?: localStore.listingsOn(botId)
        val noAction = kbLabels.filter { label ->
            // Кнопки визарда («Разместить объявление», «Мои объявления»,
            // «Отмена») действие имеют — их ведёт режим «Объявления».
            if (listingsOn && ListingEngine.looksLikeTrigger(label)) return@filter false
            rulesNow.none { r ->
                r.enabled && r.pattern.isNotBlank() &&
                    (r.pattern.equals(label, ignoreCase = true) ||
                        label.contains(r.pattern, ignoreCase = true))
            }
        }
        if (noAction.isNotEmpty()) {
            done.add("без действия: ${noAction.size}")
            DeviceLlm.log("⚠️ Кнопки клавиатуры без действия: ${noAction.joinToString(", ")}")
        }
    }

    // ---------- расписание ----------
    var schedButtons = 0
    if (parsed.schedule.isNotEmpty() && wanted(ScriptImporter.SEC_SCHEDULE)) {
        val current = localStore.schedule(botId).toMutableList()
        var added = 0
        var replaced = 0
        for (ev in parsed.schedule) {
            val same = current.indexOfFirst {
                it.hour == ev.hour && it.minute == ev.minute && it.days == ev.days
            }
            val fixed = ev.copy(menu = buttonsFixed(ev.menu), id = if (same >= 0) current[same].id else ev.id)
            if (same >= 0) { current[same] = fixed; replaced++ } else { current.add(fixed); added++ }
            schedButtons += ev.menu.size
        }
        localStore.setSchedule(current, botId)
        // В оригинале напоминания включаются сразу после запуска — иначе
        // «напоминания не приходят».
        localStore.setRemindersOn(true, botId)
        done.add("событий: +$added (обновлено $replaced)")
    }

    // ---------- канал и объявления ----------
    if (wanted(ScriptImporter.SEC_CHANNEL)) {
        parsed.channel?.let { localStore.setChannelId(it, botId); done.add("канал $it") }
        parsed.listingsOn?.let { localStore.setListingsOn(it, botId) }
    }

    val totalButtons = ruleButtons + schedButtons
    if (totalButtons > 0) done.add("кнопок: $totalButtons")
    else if (parsed.keyboardRows.isEmpty()) {
        // В скрипте нет ни inline-меню, ни клавиатуры чата — говорим прямо,
        // иначе «Выбирай кнопки ниже» остаётся без кнопок.
        done.add("кнопок в скрипте нет")
        DeviceLlm.log("ℹ️ В скрипте не найдено кнопок (ни inline-меню, ни клавиатуры чата). " +
            "Задай их в «Правила ответов» (меню сообщения) или включи режим «Объявления».")
    }

    return if (done.isEmpty()) "Нечего применять — отметь хотя бы одну категорию"
    else done.joinToString(", ")
}

private fun newPackId(imported: String): String {
    val base = imported.removePrefix("imp_").lowercase()
    val clean = base.replace(Regex("[^a-z0-9_]+"), "_").trim('_')
    return if (clean.isBlank()) "pack" + System.currentTimeMillis().toString().takeLast(6)
    else "pack_$clean".take(40)
}
