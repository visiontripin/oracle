package com.botcontrol.admin.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.botcontrol.admin.llm.DeviceLlm
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Журнал событий бота (загрузки, напоминания, ошибки Telegram/ИИ).
 * Текст выделяется удержанием и копируется; есть «Копировать всё».
 */
@Composable
fun LogScreen(onBack: () -> Unit) {
    val log by DeviceLlm.log.collectAsState()
    val clipboard = LocalClipboardManager.current
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    val fullText = remember(log) {
        log.joinToString("\n") { entry ->
            "${timeFormat.format(Date(entry.epochSec * 1000))}  ${entry.text}"
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text("Журнал событий", style = MaterialTheme.typography.titleLarge)
        }
        Text("Удерживай палец на тексте, чтобы выделить и скопировать часть.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.padding(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                clipboard.setText(AnnotatedString(fullText))
            }, modifier = Modifier.weight(1f)) { Text("📋 Копировать всё") }
            OutlinedButton(onClick = {
                DeviceLlm.log("🧺 Журнал очищен")
            }, modifier = Modifier.weight(1f)) { Text("Очистить") }
        }
        Spacer(Modifier.padding(6.dp))
        SelectionContainer {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                if (log.isEmpty()) {
                    Text("Пока пусто — здесь появятся события ботов.",
                        style = MaterialTheme.typography.bodySmall)
                }
                log.forEach { entry ->
                    Text(
                        "${timeFormat.format(Date(entry.epochSec * 1000))}  ${entry.text}",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = MaterialTheme.typography.bodySmall.fontSize,
                            color = MaterialTheme.colorScheme.onBackground,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                    )
                }
                Spacer(Modifier.padding(16.dp))
            }
        }
    }
}
