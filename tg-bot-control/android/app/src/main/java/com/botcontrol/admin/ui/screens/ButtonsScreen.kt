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
    var events by remember { mutableStateOf<List<ScheduleEvent>>(emptyList()) }

    fun load() {
        scope.launch {
            botId = localStore.activeBotId()
            keyboard = localStore.keyboard(botId)
            rules = repository.botRules(botId)
            events = localStore.schedule(botId)
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
                Text("Показывается всем, кто пишет боту, и шлёт текст кнопки в чат. "
                    + "Чтобы кнопка что-то делала — создай правило с типом «Кнопка» и тем же текстом "
                    + "(Сценарии → Правила ответов). Раскладываются по две в ряд.",
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
                        Text("🔘 $label", Modifier.weight(1f))
                        TextButton(onClick = { editing = label }) { Text("✎") }
                        TextButton(onClick = { confirm.value = ConfirmRequest("Удалить кнопку?", "«$label»") {
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
                TextButton(onClick = {
                    scope.launch {
                        val known = rules.filter { it.type == "button" }.map { it.pattern }
                        val missing = keyboard.filter { it !in known }
                        message = if (missing.isEmpty()) "✅ У каждой кнопки есть правило"
                        else "⚠️ Нет правила для: ${missing.joinToString(", ")} — создай «Кнопка» в правилах"
                    }
                }) { Text("🔍 Проверить, что у кнопок есть правила") }
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

    // ---------- диалог добавления/изменения ----------
    if (showAdd || editing != null) {
        var label by remember { mutableStateOf(editing ?: "") }
        AlertDialog(
            onDismissRequest = { showAdd = false; editing = null },
            title = { Text(if (editing == null) "Новая кнопка" else "Изменить кнопку") },
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
                            val next = if (editing == null) keyboard + label.trim()
                            else keyboard.map { if (it == editing) label.trim() else it }
                            localStore.setKeyboard(next, botId)
                            message = if (editing == null)
                                "✅ Добавлено: «${label.trim()}» (бот $botId)"
                            else "✅ Сохранено (бот $botId)"
                            showAdd = false
                            editing = null
                            load()
                        }
                    },
                    enabled = label.isNotBlank(),
                ) { Text(if (editing == null) "Добавить" else "Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false; editing = null }) { Text("Отмена") }
            },
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
