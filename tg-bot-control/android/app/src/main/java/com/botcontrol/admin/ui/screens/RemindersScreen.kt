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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import com.botcontrol.admin.data.InlineBtn
import com.botcontrol.admin.data.LocalBotStore
import com.botcontrol.admin.data.ReplyPack
import com.botcontrol.admin.data.ScheduleEvent
import com.botcontrol.admin.llm.DeviceLlm
import com.botcontrol.admin.ui.components.DropdownField
import com.botcontrol.admin.ui.components.MenuEditor
import com.botcontrol.admin.ui.components.SectionTitle
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Расписание напоминаний: время, текст, дни недели, кнопки под сообщением.
 * Всё редактируется списками и выпадающими меню — без кода.
 */
@Composable
fun RemindersScreen(localStore: LocalBotStore, onBack: () -> Unit) {
    val confirm = remember { mutableStateOf<ConfirmRequest?>(null) }
    ConfirmHost(confirm)
    val scope = rememberCoroutineScope()
    var events by remember { mutableStateOf<List<ScheduleEvent>>(emptyList()) }
    var packs by remember { mutableStateOf<List<ReplyPack>>(emptyList()) }
    var remindersOn by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ScheduleEvent?>(null) }

    fun load() {
        scope.launch {
            events = localStore.schedule()
            packs = localStore.packs()
            remindersOn = localStore.remindersOn()
        }
    }
    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text("Напоминания", style = MaterialTheme.typography.titleLarge)
        }

        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Напоминания включены")
                    Text("Сработают, пока бот запущен, а в чате нажата кнопка «▶️ Запуск» (или здесь)",
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(remindersOn, onCheckedChange = {
                    remindersOn = it
                    scope.launch { localStore.setRemindersOn(it) }
                })
            }
        }

        SectionTitle("События")
        if (events.isEmpty()) {
            Text("Пока ничего не запланировано.", style = MaterialTheme.typography.bodySmall)
        }
        events.sortedBy { it.hour * 60 + it.minute }.forEach { event ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${event.timeLabel()} — ${event.text}",
                            style = MaterialTheme.typography.titleSmall)
                        Text(event.daysLabel() +
                            if (event.menu.isNotEmpty()) " • кнопок: ${event.menu.size}" else "",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { editing = event }) { Text("✎") }
                    Switch(event.enabled, onCheckedChange = { on ->
                        scope.launch {
                            localStore.setSchedule(
                                localStore.schedule().map {
                                    if (it.id == event.id) it.copy(enabled = on) else it
                                })
                            load()
                        }
                    })
                    TextButton(onClick = { confirm.value = ConfirmRequest("Удалить событие?", "${event.timeLabel()} — ${event.text.take(80)}") {
                        scope.launch {
                            localStore.setSchedule(localStore.schedule().filter { it.id != event.id })
                            load()
                        }
                    } }) { Text("✕") }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Button(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
            Text("+ Добавить событие")
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showAdd || editing != null) {
        EventDialog(
            initial = editing,
            packs = packs,
            onDismiss = { showAdd = false; editing = null },
            onSave = { saved ->
                scope.launch {
                    val current = localStore.schedule()
                    val next = if (editing == null) {
                        current + saved.copy(id = "ev" + UUID.randomUUID().toString().substring(0, 8))
                    } else {
                        current.map { if (it.id == editing!!.id) saved.copy(id = it.id) else it }
                    }
                    localStore.setSchedule(next)
                    showAdd = false
                    editing = null
                    load()
                }
            },
        )
    }
}

@Composable
private fun EventDialog(
    initial: ScheduleEvent?,
    packs: List<ReplyPack>,
    onDismiss: () -> Unit,
    onSave: (ScheduleEvent) -> Unit,
) {
    var hour by remember { mutableStateOf(initial?.hour ?: 9) }
    var minute by remember { mutableStateOf(initial?.minute ?: 0) }
    var text by remember { mutableStateOf(initial?.text ?: "") }
    var days by remember { mutableStateOf(initial?.days ?: listOf(1, 2, 3, 4, 5, 6, 7)) }
    var menu by remember { mutableStateOf(initial?.menu ?: emptyList<InlineBtn>()) }
    var toChannel by remember { mutableStateOf(initial?.toChannel ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Новое событие" else "Событие") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DropdownField(
                        value = hour, options = (0..23).toList(),
                        label = "Час", display = { "%02d".format(it) },
                        onSelect = { hour = it }, modifier = Modifier.weight(1f),
                    )
                    DropdownField(
                        value = minute, options = (0..59).toList(),
                        label = "Минута", display = { "%02d".format(it) },
                        onSelect = { minute = it }, modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(text, { text = it },
                    label = { Text("Текст напоминания") },
                    placeholder = { Text("🔥 ПЕРЕКУР") },
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = days.size == 7, onClick = {
                        days = listOf(1, 2, 3, 4, 5, 6, 7)
                    }, label = { Text("Всегда") })
                    FilterChip(selected = days == listOf(1, 2, 3, 4, 5), onClick = {
                        days = listOf(1, 2, 3, 4, 5)
                    }, label = { Text("Будни") })
                    FilterChip(selected = days == listOf(6, 7), onClick = {
                        days = listOf(6, 7)
                    }, label = { Text("Выходные") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ScheduleEvent.DAY_NAMES.forEachIndexed { index, dayName ->
                        FilterChip(
                            selected = days.contains(index + 1),
                            onClick = {
                                days = if (days.contains(index + 1)) days - (index + 1)
                                else days + (index + 1)
                            },
                            label = { Text(dayName) },
                        )
                    }
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = toChannel, onCheckedChange = { toChannel = it })
                    Text("📢 Публиковать в канал (не в чат)", style = MaterialTheme.typography.bodySmall)
                }
                Text("Варианты текста через « | » — ротация по дням: «Доброе утро! | Новый день — новые вещи!»",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                MenuEditor(menu = menu, packs = packs, onChange = { menu = it })
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(ScheduleEvent(
                        id = initial?.id.orEmpty(),
                        hour = hour, minute = minute, text = text.trim(),
                        days = days.sorted(), menu = menu, toChannel = toChannel,
                    ))
                },
                enabled = text.isNotBlank() && days.isNotEmpty(),
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
