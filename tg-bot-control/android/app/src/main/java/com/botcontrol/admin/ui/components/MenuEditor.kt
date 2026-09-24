package com.botcontrol.admin.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.botcontrol.admin.data.InlineBtn
import com.botcontrol.admin.data.ReplyPack
import java.util.UUID

/**
 * Редактор inline-меню сообщения (до 3 кнопок): надпись, всплывашка,
 * действие (текст / случайное из набора / вкл-выкл напоминаний / скрипт).
 */
@Composable
fun MenuEditor(
    menu: List<InlineBtn>,
    packs: List<ReplyPack>,
    onChange: (List<InlineBtn>) -> Unit,
) {
    var editing by remember { mutableStateOf<InlineBtn?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    Column {
        Text("Кнопки под этим сообщением (до 3):", style = MaterialTheme.typography.bodySmall)
        menu.forEach { btn ->
            Card(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("🔘 ${btn.label}", style = MaterialTheme.typography.bodyMedium)
                        Text(actionLabel(btn, packs), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { editing = btn }) { Text("✎") }
                    TextButton(onClick = { onChange(menu - btn) }) { Text("✕") }
                }
            }
        }
        if (menu.size < 3) {
            OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
                Text("+ Добавить кнопку")
            }
        }
    }

    if (showAdd || editing != null) {
        val initial = editing ?: InlineBtn()
        MenuButtonDialog(
            initial = initial,
            packs = packs,
            onDismiss = { showAdd = false; editing = null },
            onSave = { saved ->
                val next = if (editing == null) {
                    menu + saved.copy(id = "cb" + UUID.randomUUID().toString().substring(0, 8))
                } else {
                    menu.map { if (it.id == editing!!.id) saved.copy(id = it.id) else it }
                }
                onChange(next)
                showAdd = false
                editing = null
            },
        )
    }
}

private fun actionLabel(btn: InlineBtn, packs: List<ReplyPack>): String = when (btn.action) {
    "pack" -> "→ случайное из «${packs.firstOrNull { it.id == btn.packId }?.name ?: btn.packId}»"
    "reminders_on" -> "→ включить напоминания"
    "reminders_off" -> "→ выключить напоминания"
    "script" -> "→ скрипт JS"
    else -> "→ текст: ${btn.text.take(30)}"
}

@Composable
private fun MenuButtonDialog(
    initial: InlineBtn,
    packs: List<ReplyPack>,
    onDismiss: () -> Unit,
    onSave: (InlineBtn) -> Unit,
) {
    var label by remember { mutableStateOf(initial.label) }
    var toast by remember { mutableStateOf(initial.toast) }
    var action by remember { mutableStateOf(initial.action) }
    var packId by remember { mutableStateOf(initial.packId) }
    var text by remember { mutableStateOf(initial.text) }
    var script by remember { mutableStateOf(initial.script) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Кнопка") },
        text = {
            Column {
                OutlinedTextField(label, { label = it },
                    label = { Text("Надпись на кнопке") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(toast, { toast = it },
                    label = { Text("Всплывашка при нажатии (необязательно)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                DropdownField(
                    value = action,
                    options = listOf("text", "pack", "reminders_on", "reminders_off", "script"),
                    label = "Что сделать",
                    display = {
                        when (it) {
                            "pack" -> "Случайное из набора"
                            "reminders_on" -> "Включить напоминания"
                            "reminders_off" -> "Выключить напоминания"
                            "script" -> "Скрипт JS"
                            else -> "Ответить текстом"
                        }
                    },
                    onSelect = {
                        action = it
                        // Иначе «Сохранить» заблокирована: видно первый набор,
                        // а packId пуст -> кнопки «не добавляются».
                        if (it == "pack" && packId.isBlank()) {
                            packId = packs.firstOrNull()?.id.orEmpty()
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                when (action) {
                    "pack" -> DropdownField(
                        value = packId.ifBlank { packs.firstOrNull()?.id.orEmpty() },
                        options = packs.map { it.id },
                        label = "Набор ответов",
                        display = { id -> packs.firstOrNull { it.id == id }?.name ?: id },
                        onSelect = { packId = it },
                    )
                    "script" -> OutlinedTextField(script, { script = it },
                        label = { Text("JS: function handle(e) { … return \"ответ\" }") },
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().height(190.dp))
                    "reminders_on", "reminders_off" -> OutlinedTextField(text, { text = it },
                        label = { Text("Текст после переключения") },
                        modifier = Modifier.fillMaxWidth().height(90.dp))
                    else -> OutlinedTextField(text, { text = it },
                        label = { Text("Ответ бота") },
                        modifier = Modifier.fillMaxWidth().height(90.dp))
                }
            }
        },
        confirmButton = {
            val resolvedPack = packId.ifBlank { packs.firstOrNull()?.id.orEmpty() }
            TextButton(
                onClick = { onSave(InlineBtn(action = action, label = label.trim(), toast = toast,
                    packId = resolvedPack, text = text, script = script)) },
                enabled = label.isNotBlank() && when (action) {
                    "pack" -> resolvedPack.isNotBlank()
                    "text" -> text.isNotBlank()
                    "script" -> script.isNotBlank()
                    else -> true
                },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
