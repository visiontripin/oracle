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
import com.botcontrol.admin.data.LocalBotStore
import com.botcontrol.admin.data.ReplyPack
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Наборы ответов: именованные списки, из которых бот присылает случайное
 * (шутки, сарказм «покурил / остался здоровым» — как в оригинальном боте).
 */
@Composable
fun PacksScreen(localStore: LocalBotStore, onBack: () -> Unit, onEditPack: (String) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var packs by remember { mutableStateOf<List<ReplyPack>>(emptyList()) }
    var openPackId by remember { mutableStateOf("") }
    var showAddPack by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<ReplyPack?>(null) }
    var botNames by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }

    fun load() {
        scope.launch {
            packs = localStore.packs()
            botNames = localStore.profiles().associate { p ->
                p.id to (p.username.takeIf { it.isNotBlank() }?.let { "@$it" } ?: p.name)
            }
        }
    }
    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text("Наборы ответов", style = MaterialTheme.typography.titleLarge)
        }
        Text("Из этих списков бот присылает случайное — по кнопкам и правилам с действием «Набор». "
            + "Свои списки можно импортировать кодом: Скрипты → Импорт настроек из кода "
            + "(список строк вида NAME = [ «…», ] станет набором).",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))

        Spacer(Modifier.height(8.dp))
        if (packs.isEmpty()) {
            Text("Наборов пока нет.", style = MaterialTheme.typography.bodySmall)
        }
        packs.forEach { pack ->
            val isOpen = openPackId == pack.id
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(pack.name.ifBlank { "Без названия" },
                                style = MaterialTheme.typography.titleSmall)
                            val owner = if (pack.ownerBotId == 0L) "общий"
                                else "бот " + (botNames[pack.ownerBotId] ?: "#${pack.ownerBotId}")
                            Text("ответов: ${pack.items.size} · $owner",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { onEditPack(pack.id) }) { Text("✎ Изменить") }
                        TextButton(onClick = {
                            openPackId = if (isOpen) "" else pack.id
                        }) { Text(if (isOpen) "▲" else "▼") }
                        TextButton(onClick = { confirmDelete = pack }) { Text("✕") }
                    }
                    if (isOpen) {
                        PackItemsEditor(pack = pack, localStore = localStore, onSaved = { load() })
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Button(onClick = { showAddPack = true }, modifier = Modifier.fillMaxWidth()) {
            Text("+ Новый набор")
        }
        confirmDelete?.let { victim ->
            AlertDialog(
                onDismissRequest = { confirmDelete = null },
                title = { Text("Удалить набор?") },
                text = {
                    Text("«${victim.name.ifBlank { "Без названия" }}» (ответов: ${victim.items.size}). " +
                        "Правила и кнопки, которые на него ссылаются, перестанут отвечать.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            localStore.setPacks(localStore.packs().filterNot { it.id == victim.id })
                            confirmDelete = null
                            load()
                        }
                    }) { Text("Удалить") }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Отмена") } },
            )
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showAddPack) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddPack = false },
            title = { Text("Новый набор") },
            text = {
                OutlinedTextField(name, { name = it },
                    label = { Text("Название, например «Поздравления»") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            localStore.setPacks(localStore.packs() + ReplyPack(
                                id = "pack" + UUID.randomUUID().toString().substring(0, 8),
                                name = name.trim(),
                            ))
                            showAddPack = false
                            load()
                        }
                    },
                    enabled = name.isNotBlank(),
                ) { Text("Создать") }
            },
            dismissButton = { TextButton(onClick = { showAddPack = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun PackItemsEditor(
    pack: ReplyPack,
    localStore: LocalBotStore,
    onSaved: () -> Unit,
) {
    val confirm = remember { mutableStateOf<ConfirmRequest?>(null) }
    ConfirmHost(confirm)
    val scope = rememberCoroutineScope()
    var newItem by remember { mutableStateOf("") }

    Column {
        pack.items.forEachIndexed { index, item ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}. ${item.take(70)}",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { confirm.value = ConfirmRequest("Удалить ответ из набора?", item.take(160)) {
                    scope.launch {
                        localStore.setPacks(localStore.packs().map {
                            if (it.id == pack.id) it.copy(items = it.items - item) else it
                        })
                        onSaved()
                    }
                } }) { Text("✕") }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(newItem, { newItem = it },
                label = { Text("Новый ответ") },
                modifier = Modifier.weight(1f))
            Button(onClick = {
                scope.launch {
                    localStore.setPacks(localStore.packs().map {
                        if (it.id == pack.id) it.copy(items = it.items + newItem.trim()) else it
                    })
                    newItem = ""
                    onSaved()
                }
            }, enabled = newItem.isNotBlank()) { Text("+") }
        }
    }
}
