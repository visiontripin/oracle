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
import com.botcontrol.admin.data.LocalBotStore
import kotlinx.coroutines.launch

/**
 * Кнопки бота — один экран без дублей:
 * 1) клавиатура снизу в Telegram (добавить / изменить / удалить);
 * 2) обзор кнопок под сообщениями (inline) с подсказкой, где их править.
 */
@Composable
fun ButtonsScreen(localStore: LocalBotStore, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var botId by remember { mutableStateOf(0L) }
    var keyboard by remember { mutableStateOf<List<String>>(emptyList()) }
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }

    fun load() {
        scope.launch {
            botId = localStore.activeBotId()
            keyboard = localStore.keyboard(botId)
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
                Text("Показывается всем, кто пишет боту. Чтобы кнопка что-то делала — создай правило с типом «Кнопка» и тем же текстом (Сценарии → Правила).",
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
                        TextButton(onClick = {
                            scope.launch {
                                localStore.setKeyboard(keyboard - label, botId)
                                message = "🗑 Удалено (бот $botId)"
                                load()
                            }
                        }) { Text("✕") }
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
                Text("Настраиваются там же, где создаётся сообщение — они «прикреплены» к нему:\n• у напоминания (Сценарии → Напоминания → ✎ событие → «Добавить кнопку»);\n• у правила-ответа (Сценарии → Правила → ✎ → блок кнопок);\n• у приветствия /start (Меню → Меню и приветствие).",
                    style = MaterialTheme.typography.bodySmall)
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
