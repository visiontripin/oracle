package com.botcontrol.admin.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import com.botcontrol.admin.data.Anim
import com.botcontrol.admin.data.AnimSpec
import com.botcontrol.admin.data.InlineBtn
import com.botcontrol.admin.data.ReplyPack
import java.util.UUID

/**
 * Редактор inline-меню сообщения (до [MAX_MENU_BUTTONS] кнопок): надпись, всплывашка,
 * действие (текст / случайное из набора / вкл-выкл напоминаний / скрипт /
 * анимация правкой сообщения / кубик).
 */
/** Сколько кнопок можно добавить под одно сообщение (Telegram допускает больше). */
const val MAX_MENU_BUTTONS = 12
/** Сколько рядов предлагать в выборе «Ряд кнопок». */
const val MAX_MENU_ROWS = 8

@Composable
fun MenuEditor(
    menu: List<InlineBtn>,
    packs: List<ReplyPack>,
    onChange: (List<InlineBtn>) -> Unit,
) {
    var editing by remember { mutableStateOf<InlineBtn?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<InlineBtn?>(null) }

    Column {
        Text("Кнопки под этим сообщением (до $MAX_MENU_BUTTONS):", style = MaterialTheme.typography.bodySmall)
        menu.forEach { btn ->
            Card(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("🔘 ${btn.label}", style = MaterialTheme.typography.bodyMedium)
                        Text(actionLabel(btn, packs), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { editing = btn }) { Text("✎") }
                    TextButton(onClick = { deleting = btn }) { Text("✕") }
                }
            }
        }
        if (menu.size < MAX_MENU_BUTTONS) {
            OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
                Text("+ Добавить кнопку")
            }
        }
    }

    deleting?.let { btn ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Удалить кнопку?") },
            text = { Text("«${btn.label}» исчезнет из меню этого сообщения.") },
            confirmButton = {
                TextButton(onClick = { onChange(menu - btn); deleting = null }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } },
        )
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

private fun actionLabel(btn: InlineBtn, packs: List<ReplyPack>): String {
    val base = when (btn.action) {
        "pack" -> "→ случайное из «${packs.firstOrNull { it.id == btn.packId }?.name ?: btn.packId}»"
        "reminders_on" -> "→ включить напоминания"
        "reminders_off" -> "→ выключить напоминания"
        "script" -> "→ скрипт JS"
        "anim" -> "→ 🎞 " + Anim.describe(Anim.decode(btn.script))
        "dice" -> "→ кубик ${btn.text.ifBlank { "🎲" }}"
        "url" -> "→ ссылка: ${btn.url.take(30)}"
        else -> "→ текст: ${btn.text.take(30)}"
    }
    val flags = buildList {
        if (btn.edit) add("правит сообщение")
        if (btn.row > 0) add("ряд ${btn.row}")
    }
    return base + if (flags.isEmpty()) "" else " (${flags.joinToString(", ")})"
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
    var script by remember { mutableStateOf(if (initial.action == "anim") "" else initial.script) }
    var url by remember { mutableStateOf(initial.url) }
    var edit by remember { mutableStateOf(initial.edit) }
    var row by remember { mutableStateOf(if (initial.row > 0) initial.row else 1) }
    var toastSame by remember { mutableStateOf(initial.toastNoChange) }
    var animSpec by remember {
        mutableStateOf(if (initial.action == "anim") Anim.decode(initial.script)
            else AnimSpec(preset = "spinner", text = "Загрузка", intervalMs = 600))
    }
    var dice by remember { mutableStateOf(if (initial.action == "dice") initial.text.ifBlank { "🎲" } else "🎲") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Кнопка") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
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
                    options = listOf("text", "pack", "anim", "dice", "url", "reminders_on", "reminders_off", "script"),
                    label = "Что сделать",
                    display = {
                        when (it) {
                            "pack" -> "Случайное из набора"
                            "url" -> "Открыть ссылку"
                            "reminders_on" -> "Включить напоминания"
                            "reminders_off" -> "Выключить напоминания"
                            "script" -> "Скрипт JS"
                            "anim" -> "🎞 Анимация (правка сообщения)"
                            "dice" -> "🎲 Кубик / дартс / слот"
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
                    "anim" -> AnimEditor(spec = animSpec, packs = packs, onChange = { animSpec = it })
                    "dice" -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Anim.DICE.forEach { d ->
                            FilterChip(selected = dice == d, onClick = { dice = d }, label = { Text(d) })
                        }
                    }
                    "url" -> {
                        OutlinedTextField(url, { url = it },
                            label = { Text("Ссылка") },
                            placeholder = { Text("https://… или tg://user?id=12345") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text("Кнопка-ссылка ничего не шлёт боту — Telegram просто открывает адрес.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    "reminders_on", "reminders_off" -> {
                        OutlinedTextField(text, { text = it },
                            label = { Text("Текст после переключения") },
                            modifier = Modifier.fillMaxWidth().height(90.dp))
                        OutlinedTextField(toastSame, { toastSame = it },
                            label = { Text("Всплывашка, если уже так (необязательно)") },
                            placeholder = { Text("Уже работает") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    else -> OutlinedTextField(text, { text = it },
                        label = { Text("Ответ бота") },
                        modifier = Modifier.fillMaxWidth().height(90.dp))
                }
                if (action == "text" || action == "pack" || action == "anim") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = edit, onCheckedChange = { edit = it })
                        Text(if (action == "anim") "Анимировать это же сообщение (кнопки останутся)"
                            else "Править это сообщение (не присылать новое)",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(4.dp))
                DropdownField(
                    value = row,
                    options = (1..maxOf(MAX_MENU_ROWS, row)).toList(),
                    label = "Ряд кнопок",
                    display = { "Ряд $it" },
                    onSelect = { row = it },
                )
            }
        },
        confirmButton = {
            val resolvedPack = packId.ifBlank { packs.firstOrNull()?.id.orEmpty() }
            TextButton(
                onClick = { onSave(InlineBtn(action = action, label = label.trim(), toast = toast,
                    packId = if (action == "pack") resolvedPack else "",
                    text = if (action == "dice") dice else text,
                    script = if (action == "anim") Anim.encode(animSpec) else script,
                    url = if (action == "url") url.trim() else "", edit = edit, row = row,
                    toastNoChange = if (action.startsWith("reminders")) toastSame.trim() else "")) },
                enabled = label.isNotBlank() && when (action) {
                    "pack" -> resolvedPack.isNotBlank()
                    "text" -> text.isNotBlank()
                    "script" -> script.isNotBlank()
                    "url" -> url.trim().startsWith("http") || url.trim().startsWith("tg://")
                    "anim" -> animSpec.preset != "custom" || animSpec.frames.isNotEmpty()
                    else -> true
                },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
