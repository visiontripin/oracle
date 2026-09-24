package com.botcontrol.admin.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.botcontrol.admin.data.LocalBotStore
import com.botcontrol.admin.data.local.BotRuleEntity
import com.botcontrol.admin.data.BotRepository
import com.botcontrol.admin.service.LocalBotService
import com.botcontrol.admin.ui.components.ErrorText
import com.botcontrol.admin.ui.components.SectionTitle
import kotlinx.coroutines.launch

/**
 * Home of the on-phone bot: Start/Stop, «команда → ответ», кнопки
 * клавиатуры Telegram, скрипты и ИИ. Всё кнопками, без логина.
 */
@Composable
fun LocalBotScreen(
    repository: BotRepository,
    localStore: LocalBotStore,
    onManageModels: () -> Unit,
    onLlmChat: () -> Unit,
    onOpenScript: (Int) -> Unit,
    onReminders: () -> Unit,
    onPacks: () -> Unit,
    onAiSettings: () -> Unit,
    onBotSettings: () -> Unit,
    onSimulate: () -> Unit,
    onChangeMode: () -> Unit,
    onEditToken: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val states by LocalBotService.states.collectAsState()
    var activeId by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) { activeId = localStore.activeBotId() }
    val state = states[activeId]
        ?: com.botcontrol.admin.service.LocalBotState(botId = activeId)

    var rules by remember { mutableStateOf<List<BotRuleEntity>>(emptyList()) }
    var keyboard by remember { mutableStateOf<List<String>>(emptyList()) }
    var llmEnabled by remember { mutableStateOf(true) }
    var systemPrompt by remember { mutableStateOf("") }
    var defaultReply by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var showAddRule by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<BotRuleEntity?>(null) }
    var showAddButton by remember { mutableStateOf(false) }
    var editingButton by remember { mutableStateOf<String?>(null) }
    var packs by remember { mutableStateOf<List<com.botcontrol.admin.data.ReplyPack>>(emptyList()) }
    var showPerkur by remember { mutableStateOf(false) }

    fun load() {
        scope.launch {
            rules = repository.botRules(localStore.activeBotId())
            keyboard = localStore.keyboard()
            packs = localStore.packs()
            llmEnabled = localStore.llmEnabled()
            systemPrompt = localStore.systemPrompt()
            defaultReply = localStore.defaultReply()
        }
    }

    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Мой бот", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = onChangeMode) { Text("Сменить режим") }
        }
        if (error.isNotBlank()) ErrorText(error)
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)

        // ---- status ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    if (state.running) "🟢 Работает" else "⚪️ Остановлен",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (state.running) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.botUsername.isNotBlank()) {
                    Text("Бот: @${state.botUsername}")
                }
                Text("Обработано сообщений: ${state.processed}",
                    style = MaterialTheme.typography.bodySmall)
                val pollAgo = if (state.lastPollAt == 0L) -1L
                    else (System.currentTimeMillis() / 1000 - state.lastPollAt)
                Text(
                    if (pollAgo < 0) "Опрос Telegram: ещё не выполнялся"
                    else "Опрос Telegram: $pollAgo с назад",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (pollAgo in 0..60) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.lastError.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Проблема: ${state.lastError}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                LocalBotService.startBot(context, activeId)
                scope.launch { delay800(); load() }
            }, enabled = !state.running && activeId > 0, modifier = Modifier.weight(1f)) {
                Text("▶ Запустить бота") }
            OutlinedButton(onClick = {
                LocalBotService.stopBot(context, activeId)
                scope.launch { delay300(); load() }
            }, enabled = state.running && activeId > 0, modifier = Modifier.weight(1f)) {
                Text("■ Остановить бота") }
        }

        // ---- rules ----
        SectionTitle("Команды и ответы")
        Text("Что напишут в Telegram — бот ответит сам. Тип действия: текст, скрипт или ИИ.",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        if (rules.isEmpty()) {
            Text("Пока нет ни одной команды.", style = MaterialTheme.typography.bodySmall)
        }
        rules.forEach { rule ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)
                .clickable { editingRule = rule }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val kindLabel = when (rule.type) {
                            "command" -> "Команда: ${rule.pattern}"
                            "button" -> "Кнопка: «${rule.pattern}»"
                            else -> "Содержит: «${rule.pattern}»"
                        }
                        Text(kindLabel, style = MaterialTheme.typography.titleSmall)
                        val actionLabel = when (rule.actionType) {
                            "script" -> "⚙️ скрипт JS"
                            "llm" -> "🤖 отвечает ИИ"
                            else -> "Ответ: ${rule.responseText.take(50)}"
                        }
                        Text(actionLabel, style = MaterialTheme.typography.bodySmall)
                    }
                    if (rule.actionType == "script") {
                        TextButton(onClick = { onOpenScript(rule.id) }) { Text("✎ код") }
                    }
                    TextButton(onClick = { editingRule = rule }) { Text("Изменить") }
                    Switch(rule.enabled, onCheckedChange = { on ->
                        scope.launch {
                            repository.saveBotRule(rule.copy(enabled = on))
                            load()
                        }
                    })
                    TextButton(onClick = {
                        scope.launch { repository.deleteBotRule(rule.id); load() }
                    }) { Text("✕") }
                }
            }
        }
        OutlinedButton(onClick = { showAddRule = true }, modifier = Modifier.fillMaxWidth()) {
            Text("+ Добавить команду")
        }

        // ---- keyboard buttons ----
        SectionTitle("Кнопки бота (клавиатура в Telegram)")
        Text("Кнопки показываются под полем ввода. Нажатие шлёт текст кнопки — добавь правило с типом «Кнопка» для реакции.",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        if (keyboard.isEmpty()) {
            Text("Кнопок пока нет.", style = MaterialTheme.typography.bodySmall)
        }
        keyboard.forEach { label ->
            Card(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("🔘 $label", Modifier.weight(1f))
                    TextButton(onClick = { editingButton = label }) { Text("✎") }
                    TextButton(onClick = {
                        scope.launch {
                            localStore.setKeyboard(keyboard - label)
                            load()
                        }
                    }) { Text("✕") }
                }
            }
        }
        OutlinedButton(onClick = { showAddButton = true }, modifier = Modifier.fillMaxWidth()) {
            Text("+ Добавить кнопку")
        }

        // ---- AI ----
        SectionTitle("Искусственный интеллект (на телефоне)")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Отвечать ИИ на незнакомые сообщения")
                Text("Модель работает локально, интернет-ИИ не нужен",
                    style = MaterialTheme.typography.bodySmall)
            }
            Switch(llmEnabled, onCheckedChange = {
                llmEnabled = it
                scope.launch { localStore.setLlmEnabled(it) }
            })
        }
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            systemPrompt, { systemPrompt = it },
            label = { Text("Характер бота (системный промт)") },
            placeholder = { Text("Ты дружелюбный помощник…") },
            modifier = Modifier.fillMaxWidth().height(90.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = {
                scope.launch { localStore.setSystemPrompt(systemPrompt); message = "Характер сохранён" }
            }, modifier = Modifier.weight(1f)) { Text("Сохранить характер") }
            OutlinedButton(onClick = onManageModels, modifier = Modifier.weight(1f)) {
                Text("Модели ИИ")
            }
        }
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = onLlmChat, modifier = Modifier.fillMaxWidth()) {
            Text("💬 Чат с ИИ (и процесс раздумий)")
        }
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = onReminders, modifier = Modifier.fillMaxWidth()) {
            Text("⏰ Напоминания (расписание)")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onPacks, modifier = Modifier.weight(1f)) {
                Text("🎲 Наборы ответов")
            }
            OutlinedButton(onClick = onAiSettings, modifier = Modifier.weight(1f)) {
                Text("⚙️ Настройки ИИ")
            }
        }
        Spacer(Modifier.height(4.dp))
        Button(onClick = { showPerkur = true }, modifier = Modifier.fillMaxWidth()) {
            Text("⚡ Импорт: настроить как рабочий перкур-бот")
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onSimulate, modifier = Modifier.weight(1f)) {
                Text("🧪 Имитация")
            }
            OutlinedButton(onClick = onBotSettings, modifier = Modifier.weight(1f)) {
                Text("⚙️ Настройки бота")
            }
        }

        // ---- default reply ----
        SectionTitle("Ответ, если ничего не подошло")
        OutlinedTextField(
            defaultReply, { defaultReply = it },
            label = { Text("Можно оставить пустым") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = {
            scope.launch { localStore.setDefaultReply(defaultReply); message = "Сохранено" }
        }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить ответ") }

        // ---- token ----
        SectionTitle("Токен бота")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onEditToken, modifier = Modifier.weight(1f)) {
                Text("+ Бот (новый токен)")
            }
        }
        Text("Токен хранится в зашифрованном хранилище телефона.",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))
    }

    // ---- add/edit rule dialog ----
    if (showAddRule || editingRule != null) {
        val initial = editingRule
        var type by remember { mutableStateOf(initial?.type ?: "command") }
        var action by remember { mutableStateOf(initial?.actionType ?: "text") }
        var pattern by remember { mutableStateOf(initial?.pattern ?: "") }
        var response by remember { mutableStateOf(initial?.responseText ?: "") }
        var packId by remember { mutableStateOf(initial?.packId ?: "") }
        var ruleMenu by remember {
            mutableStateOf(com.botcontrol.admin.data.BotJson.menu(initial?.menu.orEmpty()))
        }
        LaunchedEffect(Unit) { packs = localStore.packs() }
        AlertDialog(
            onDismissRequest = { showAddRule = false; editingRule = null },
            title = { Text(if (initial == null) "Новое правило" else "Изменить правило") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = type == "command",
                            onClick = { type = "command" }, label = { Text("Команда /…") })
                        FilterChip(selected = type == "contains",
                            onClick = { type = "contains" }, label = { Text("Содержит") })
                        FilterChip(selected = type == "button",
                            onClick = { type = "button" }, label = { Text("Кнопка") })
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        pattern, { pattern = it },
                        label = { Text(when (type) {
                            "command" -> "Команда, например /start"
                            "button" -> "Текст кнопки (как на кнопке)"
                            else -> "Слово или фраза"
                        }) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = action == "text",
                            onClick = { action = "text" }, label = { Text("Текст") })
                        FilterChip(selected = action == "script",
                            onClick = { action = "script" }, label = { Text("Скрипт JS") })
                        FilterChip(selected = action == "llm",
                            onClick = { action = "llm" }, label = { Text("ИИ") })
                        FilterChip(selected = action == "pack",
                            onClick = { action = "pack" }, label = { Text("Набор") })
                    }
                    Spacer(Modifier.height(8.dp))
                    if (action == "text") {
                        OutlinedTextField(
                            response, { response = it },
                            label = { Text("Ответ бота") },
                            placeholder = { Text("Привет, {user}!") },
                            modifier = Modifier.fillMaxWidth().height(90.dp),
                        )
                    } else if (action == "script") {
                        Text("Сохранишь правило — открой «✎ код» и напиши JS-логику (функция handle(e)).",
                            style = MaterialTheme.typography.bodySmall)
                    } else if (action == "pack") {
                        com.botcontrol.admin.ui.components.DropdownField(
                            value = packId.ifBlank { packs.firstOrNull()?.id.orEmpty() },
                            options = packs.map { it.id },
                            label = "Набор ответов",
                            display = { id ->
                                packs.firstOrNull { it.id == id }?.name ?: "(создай набор)"
                            },
                            onSelect = { packId = it },
                        )
                    } else {
                        Text("Ответ сгенерирует локальный ИИ (вкладка «Модели ИИ»).",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    if (action != "llm") {
                        com.botcontrol.admin.ui.components.MenuEditor(
                            menu = ruleMenu, packs = packs,
                            onChange = { ruleMenu = it },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val id = repository.saveBotRule(
                                BotRuleEntity(
                                    id = initial?.id ?: 0,
                                    botId = initial?.botId ?: localStore.activeBotId(),
                                    enabled = initial?.enabled ?: true,
                                    type = type, pattern = pattern.trim(),
                                    responseText = if (action == "text") response else "",
                                    actionType = action,
                                    packId = if (action == "pack") packId else "",
                                    menu = com.botcontrol.admin.data.BotJson.save(ruleMenu),
                                )
                            )
                            showAddRule = false
                            editingRule = null
                            load()
                            if (action == "script" && initial == null) onOpenScript(id.toInt())
                        }
                    },
                    enabled = pattern.isNotBlank() && (action != "text" || response.isNotBlank()),
                ) { Text(if (initial == null) "Добавить" else "Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { showAddRule = false; editingRule = null }) { Text("Отмена") }
            },
        )
    }

    // ---- «как у перкур-бота»: импорт всех настроек ----
    if (showPerkur) {
        AlertDialog(
            onDismissRequest = { showPerkur = false },
            title = { Text("Настроить как перкур-бот?") },
            text = {
                Text("Одно нажатие переносит настройки рабочего бота: характер «Агент Смит», " +
                    "расписание перекуров, наборы ответов (шутки, сарказм), уточняющие вопросы, " +
                    "параметры ИИ (температура 0.7, ответ до 80 токенов, память 4 реплики, «печатает» 4 с, пауза 10 с), " +
                    "команды /start и /help с кнопками. Твои прежние правила сохранятся, " +
                    "характер и расписание будут заменены.")
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { applyPerkurPreset(context, repository, localStore); showPerkur = false; load() }
                }) { Text("Настроить") }
            },
            dismissButton = { TextButton(onClick = { showPerkur = false }) { Text("Отмена") } },
        )
    }

    // ---- add/edit keyboard button dialog ----
    if (showAddButton || editingButton != null) {
        var label by remember { mutableStateOf(editingButton ?: "") }
        AlertDialog(
            onDismissRequest = { showAddButton = false; editingButton = null },
            title = { Text(if (editingButton == null) "Новая кнопка" else "Изменить кнопку") },
            text = {
                Column {
                    OutlinedTextField(
                        label, { label = it },
                        label = { Text("Надпись на кнопке") },
                        placeholder = { Text("Например: 🎲 Анекдот") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Далее создай правило с типом «Кнопка» и тем же текстом — оно выполнит действие.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val next = if (editingButton == null) keyboard + label.trim()
                            else keyboard.map { if (it == editingButton) label.trim() else it }
                            localStore.setKeyboard(next)
                            showAddButton = false
                            editingButton = null
                            load()
                        }
                    },
                    enabled = label.isNotBlank(),
                ) { Text(if (editingButton == null) "Добавить" else "Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { showAddButton = false; editingButton = null }) { Text("Отмена") }
            },
        )
    }
}

private suspend fun delay800() = kotlinx.coroutines.delay(800)
private suspend fun delay300() = kotlinx.coroutines.delay(300)

/**
 * «Импорт: как рабочий перкур-бот» — переносит все настройки оригинала:
 * характер, расписание, наборы, уточняющие вопросы, параметры ИИ,
 * команды /start и /help. Правила пользователя не удаляются.
 */
suspend fun applyPerkurPreset(
    context: android.content.Context,
    repository: com.botcontrol.admin.data.BotRepository,
    localStore: com.botcontrol.admin.data.LocalBotStore,
) {
    val P = com.botcontrol.admin.data.PerkurPresets

    // 1) параметры ИИ (DevicePrefs — plain SharedPreferences)
    val prefs = com.botcontrol.admin.llm.DevicePrefs(context)
    prefs.temperature = 0.7f
    prefs.topK = 40
    prefs.maxTokens = 80

    // 2) разговорный режим
    localStore.setSystemPrompt(P.SMITH_PROMPT)
    localStore.setHistoryLimit(4)
    localStore.setTypingSeconds(4)
    localStore.setCooldownSec(10)
    localStore.setClarifyEnabled(true)
    localStore.setClarifyQuestions(P.CLARIFY)

    // 3) наборы ответов + расписание + включенные напоминания
    val keepPacks = localStore.packs().filterNot {
        it.id == P.PACK_JOKES || it.id == P.PACK_SMOKE_DONE || it.id == P.PACK_HEALTHY
    }
    localStore.setPacks(keepPacks + P.packs())
    localStore.setSchedule(P.schedule())
    localStore.setRemindersOn(true)

    // 4) команды /start и /help (если ещё нет)
    val botId = localStore.activeBotId()
    val rules = repository.botRules(botId)
    if (rules.none { it.type == "command" && it.pattern.replace("/", "").equals("start", true) }) {
        repository.saveBotRule(
            com.botcontrol.admin.data.local.BotRuleEntity(
                botId = botId,
                type = "command", pattern = "/start",
                responseText = "Симуляция активирована, {user}. 👋\nНажми «Запуск», чтобы включить напоминания.",
                actionType = "text",
                menu = com.botcontrol.admin.data.BotJson.save(P.startMenu()),
            )
        )
    }
    if (rules.none { it.type == "command" && it.pattern.replace("/", "").equals("help", true) }) {
        repository.saveBotRule(
            com.botcontrol.admin.data.local.BotRuleEntity(
                botId = botId,
                type = "command", pattern = "/help",
                responseText = "Кнопки:\n" +
                    "▶️ Запуск — включить напоминания\n" +
                    "⏹ Стоп — выключить напоминания\n\n" +
                    "Во время перекуров:\n" +
                    "🚬 Покурил / 💪 Остался здоровым / 😂 Рандомную шутку\n\n" +
                    "Пиши боту что угодно — отвечает Агент Смит.\n" +
                    "Если матрица отключена — уточняющие вопросы.",
                actionType = "text",
            )
        )
    }
    com.botcontrol.admin.llm.DeviceLlm.log(
        "⚡ Импортированы настройки перкур-бота: характер, расписание (${P.schedule().size}), " +
        "наборы (${P.JOKES.size}+${P.SMOKE_DONE.size}+${P.SMOKE_HEALTHY.size}), вопросы (${P.CLARIFY.size}), команды /start и /help")
}
