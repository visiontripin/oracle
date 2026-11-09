package com.botcontrol.admin.ui.screens

import com.botcontrol.admin.ui.components.ConfirmRequest
import com.botcontrol.admin.ui.components.ConfirmHost
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
import androidx.compose.material3.Switch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.botcontrol.admin.data.BotJson
import com.botcontrol.admin.data.BotRepository
import com.botcontrol.admin.data.InlineBtn
import com.botcontrol.admin.data.LocalBotStore
import com.botcontrol.admin.data.ScheduleEvent
import com.botcontrol.admin.data.withIds
import kotlinx.coroutines.launch

/**
 * Кнопки бота — один экран:
 * 1) клавиатура снизу в Telegram (добавить / изменить / удалить);
 * 2) обзор inline-кнопок под сообщениями: у каких правил и событий они есть,
 *    что делают и где правятся.
 */
@Composable
fun ButtonsScreen(localStore: LocalBotStore, repository: BotRepository, onBack: () -> Unit) {
    val confirm = remember { mutableStateOf<ConfirmRequest?>(null) }
    ConfirmHost(confirm)
    val scope = rememberCoroutineScope()
    var botId by remember { mutableStateOf(0L) }
    var keyboard by remember { mutableStateOf<List<String>>(emptyList()) }
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    var rules by remember { mutableStateOf<List<com.botcontrol.admin.data.local.BotRuleEntity>>(emptyList()) }
    var packs by remember { mutableStateOf<List<com.botcontrol.admin.data.ReplyPack>>(emptyList()) }
    var events by remember { mutableStateOf<List<ScheduleEvent>>(emptyList()) }

    fun load() {
        scope.launch {
            botId = localStore.activeBotId()
            keyboard = localStore.keyboard(botId)
            rules = repository.botRules(botId)
            events = localStore.schedule(botId)
            packs = localStore.packs()
        }
    }
    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text("Кнопки бота", style = MaterialTheme.typography.titleLarge)
        }

        // ---------- клавиатура ----------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Клавиатура снизу (в чате Telegram)",
                    style = MaterialTheme.typography.titleSmall)
                Text("Добавьте кнопку и сразу выберите, что бот сделает при нажатии. Кнопки располагаются по две в ряд.",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                if (keyboard.isEmpty()) {
                    Text("Кнопок пока нет.", style = MaterialTheme.typography.bodySmall)
                }
                if (message.isNotBlank()) {
                    Text(message, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                keyboard.forEach { label ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        val matches = rules.filter { it.type == "button" && it.pattern.equals(label, ignoreCase = true) }
                        Column(Modifier.weight(1f)) {
                            Text("🔘 $label")
                            val rule = matches.firstOrNull()
                            Text(when {
                                matches.size > 1 -> "⚠ Несколько действий — проверьте правила ответов"
                                rule == null -> "⚠ Действие не настроено"
                                !rule.enabled -> "⏸ Действие выключено"
                                rule.actionType == "text" -> "Ответ: ${rule.responseText.take(60)}"
                                rule.actionType == "pack" -> "Случайный ответ из набора: ${packs.firstOrNull { it.id == rule.packId }?.name ?: rule.packId}"
                                else -> "Действие: ${rule.actionType} (подробности в правилах ответов)"
                            }, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { editing = label }) { Text("✎") }
                        TextButton(onClick = { confirm.value = ConfirmRequest("Удалить кнопку?", "«$label» исчезнет с клавиатуры. Связанное правило сохранится в сценариях.") {
                            scope.launch {
                                localStore.setKeyboard(keyboard - label, botId)
                                message = "🗑 Удалено (бот $botId)"
                                load()
                            }
                        } }) { Text("✕") }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Button(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("+ Добавить кнопку")
                }

            }
        }

        Spacer(Modifier.height(12.dp))

        // ---------- inline ----------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Кнопки под сообщением (inline)",
                    style = MaterialTheme.typography.titleSmall)
                Text("Они «прикреплены» к сообщению, поэтому правятся там же, где оно создаётся: "
                    + "у правила-ответа (Сценарии → Правила ответов → ✎) или у напоминания "
                    + "(Сценарии → Напоминания → ✎ событие).",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))

                val ruleMenus = rules.mapNotNull { rule ->
                    val menu = BotJson.menu(rule.menu).withIds()
                    if (menu.isEmpty()) null else rule to menu
                }
                val eventMenus = events.mapNotNull { ev ->
                    val menu = ev.menu.withIds()
                    if (menu.isEmpty()) null else ev to menu
                }
                if (ruleMenus.isEmpty() && eventMenus.isEmpty()) {
                    Text("Inline-кнопок пока нет. Добавь их в правиле или в событии расписания — "
                        + "до 3 штук под одним сообщением.",
                        style = MaterialTheme.typography.bodySmall)
                }
                ruleMenus.forEach { (rule, menu) ->
                    Text("Правило «${rule.pattern}»", style = MaterialTheme.typography.bodyMedium)
                    menu.forEach { b -> ButtonLine(b) }
                    Spacer(Modifier.height(6.dp))
                }
                eventMenus.forEach { (ev, menu) ->
                    Text("Напоминание ${ev.timeLabel()} — ${ev.daysLabel()}",
                        style = MaterialTheme.typography.bodyMedium)
                    menu.forEach { b -> ButtonLine(b) }
                    Spacer(Modifier.height(6.dp))
                }

                Spacer(Modifier.height(4.dp))
                Text("Что умеет кнопка: ответить текстом, прислать случайное из набора, "
                    + "включить/выключить напоминания, открыть ссылку (URL), выполнить JS-скрипт. "
                    + "Порядок и ряды задаются в редакторе кнопки: «Ряд кнопок» — 1, 2 или 3.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text("Импорт из кода (Скрипты → Импорт настроек из кода) сам разберёт меню "
                    + "вида InlineKeyboardButton(...) и обработчик нажатий — кнопки появятся "
                    + "здесь с действиями и всплывашками.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    // Клавиатура и связанные правила остаются в прежнем формате для импорта и совместимости.
    if (showAdd || editing != null) {
        val oldLabel = editing
        val matches = rules.filter { it.type == "button" && oldLabel != null && it.pattern.equals(oldLabel, ignoreCase = true) }
        val initial = matches.firstOrNull()
        var label by remember(oldLabel) { mutableStateOf(oldLabel ?: "") }
        var action by remember(oldLabel) { mutableStateOf(initial?.actionType ?: "text") }
        var response by remember(oldLabel) { mutableStateOf(initial?.responseText.orEmpty()) }
        var packId by remember(oldLabel) { mutableStateOf(initial?.packId.orEmpty()) }
        var enabled by remember(oldLabel) { mutableStateOf(initial?.enabled ?: true) }
        val trimmed = label.trim()
        val duplicateButton = keyboard.any { it != oldLabel && it.equals(trimmed, ignoreCase = true) }
        val conflictingRule = rules.any { it.type == "button" && it.id != initial?.id && it.pattern.equals(trimmed, ignoreCase = true) }
        val valid = trimmed.isNotEmpty() && !duplicateButton && !conflictingRule && matches.size <= 1 &&
            when (action) {
                "text" -> response.isNotBlank()
                "pack" -> packs.any { it.id == packId && it.items.isNotEmpty() }
                else -> initial != null && action == initial.actionType
            }
        AlertDialog(
            onDismissRequest = { showAdd = false; editing = null },
            title = { Text(if (oldLabel == null) "Добавить кнопку" else "Настроить кнопку") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("1. Что увидит пользователь?", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(label, { label = it }, label = { Text("Надпись на кнопке") },
                        placeholder = { Text("Например: 🎲 Анекдот") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Text("2. Что произойдёт при нажатии?", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = action == "text", onClick = { action = "text" }, label = { Text("Ответ текстом") })
                        FilterChip(selected = action == "pack", onClick = { action = "pack" }, label = { Text("Из набора") })
                    }
                    if (initial != null && initial.actionType !in listOf("text", "pack")) {
                        FilterChip(selected = action == initial.actionType, onClick = { action = initial.actionType },
                            label = { Text("Сохранить прежнее действие") })
                    }
                    if (action == "text") {
                        OutlinedTextField(response, { response = it }, label = { Text("Ответ бота") },
                            modifier = Modifier.fillMaxWidth(), minLines = 2)
                    } else if (action == "pack") {
                        com.botcontrol.admin.ui.components.DropdownField(value = packId, options = packs.map { it.id },
                            label = "Набор ответов", display = { id -> packs.firstOrNull { it.id == id }?.name ?: "Выберите набор" },
                            onSelect = { packId = it })
                        if (packs.none { it.id == packId && it.items.isNotEmpty() })
                            Text("Выберите непустой набор ответов в разделе «Наборы ответов».", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("Существующее действие «$action» сохранится. Для его подробной настройки откройте «Правила ответов».", style = MaterialTheme.typography.bodySmall)
                    }
                    if (initial != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Действие включено", Modifier.weight(1f))
                            Switch(checked = enabled, onCheckedChange = { enabled = it })
                        }
                    }
                    if (duplicateButton || conflictingRule || matches.size > 1)
                        Text("Такое название уже используется или найдено несколько правил. Измените название либо устраните конфликт в правилах ответов.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Text("Если другое правило подходит под текст кнопки раньше, оно может перехватить нажатие. Проверить порядок можно в правилах ответов.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(enabled = valid, onClick = {
                    scope.launch {
                        val rule = (initial ?: com.botcontrol.admin.data.local.BotRuleEntity(
                            type = "button", pattern = trimmed, responseText = "", botId = botId)).copy(
                            type = "button", pattern = trimmed, enabled = enabled, actionType = action,
                            responseText = if (action == "text") response else if (action == initial?.actionType) initial?.responseText.orEmpty() else "",
                            packId = if (action == "pack") packId else if (action == initial?.actionType) initial?.packId.orEmpty() else "",
                        )
                        repository.saveBotRule(rule)
                        val next = if (oldLabel == null) keyboard + trimmed else keyboard.map { if (it == oldLabel) trimmed else it }
                        localStore.setKeyboard(next, botId)
                        message = "✅ Кнопка и действие сохранены"
                        showAdd = false; editing = null; load()
                    }
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false; editing = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ButtonLine(b: InlineBtn) {
    val action = when (b.action) {
        "pack" -> "→ случайное из набора ${b.packId}"
        "reminders_on" -> "→ включить напоминания"
        "reminders_off" -> "→ выключить напоминания"
        "script" -> "→ скрипт JS"
        "url" -> "→ ссылка ${b.url}"
        else -> "→ текст: ${b.text.take(30)}"
    }
    val flags = buildList {
        if (b.edit) add("правит сообщение")
        if (b.row > 0) add("ряд ${b.row}")
    }.joinToString(", ")
    Column(Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp)) {
        Text("🔘 ${b.label}", style = MaterialTheme.typography.bodySmall)
        Text(action + if (flags.isNotBlank()) " ($flags)" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
