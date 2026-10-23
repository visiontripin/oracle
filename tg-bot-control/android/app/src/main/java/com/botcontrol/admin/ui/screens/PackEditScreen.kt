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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.botcontrol.admin.data.LocalBotStore
import kotlinx.coroutines.launch

/**
 * Полноэкранный редактор набора ответов: каждый ответ — строка «кода».
 * Удобно для больших наборов (100 шуток): правишь текст как файл,
 * сохраняешь — строки становятся ответами.
 */
@Composable
fun PackEditScreen(
    packId: String,
    localStore: LocalBotStore,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    LaunchedEffect(packId) {
        val pack = localStore.packs().firstOrNull { it.id == packId }
        if (pack != null) {
            name = pack.name
            text = pack.items.joinToString("\n")
        }
        loaded = true
    }

    if (!loaded) return

    val lineCount = text.lines().count { it.isNotBlank() }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Column(Modifier.weight(1f)) {
                Text("Набор ответов", style = MaterialTheme.typography.titleLarge)
                Text("Строк: $lineCount", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = {
                scope.launch {
                    val items = text.lines()
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                    val updated = localStore.packs().map {
                        if (it.id == packId) it.copy(name = name.trim().ifBlank { it.name }, items = items)
                        else it
                    }
                    localStore.setPacks(updated)
                    message = "Сохранено: ${items.size} ответов"
                }
            }) { Text("💾 Сохранить") }
        }
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            name, { name = it },
            label = { Text("Название набора") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text("Один ответ — одна строка. Пустые строки игнорируются; можно вставить весь список из заметки или файла.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            text, { text = it },
            label = { Text("Ответы (по одному на строку)") },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace,
                fontSize = MaterialTheme.typography.bodySmall.fontSize),
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = {
                text = if (text.isBlank()) "" else text.trimEnd() + "\n"
            }, modifier = Modifier.weight(1f)) { Text("+ строка") }
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text("Готово") }
        }
        Spacer(Modifier.height(12.dp))
    }
}
