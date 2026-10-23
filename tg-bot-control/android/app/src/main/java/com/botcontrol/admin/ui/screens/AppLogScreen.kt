package com.botcontrol.admin.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.botcontrol.admin.llm.DeviceLlm
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Общий лог приложения: все события (запуск ботов, входящие сообщения,
 * решения «мозга», напоминания, кнопки, ошибки Telegram/ИИ).
 * Выделение удержанием + копирование, «Копировать всё» и экспорт в .txt.
 */
@Composable
fun AppLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val log by DeviceLlm.log.collectAsState()
    val timeFormat = remember { SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()) }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val text = pendingExport
        if (uri != null && text != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(text.toByteArray(Charsets.UTF_8))
                }
            }
        }
        pendingExport = null
    }

    val fullText = remember(log) {
        log.joinToString("\n") { entry ->
            "${timeFormat.format(Date(entry.epochSec * 1000))}  ${entry.text}"
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text("Общий лог", style = MaterialTheme.typography.titleLarge)
        }
        Text("Все события приложения. Удерживай палец на тексте, чтобы выделить часть.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.padding(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                clipboard.setText(AnnotatedString(fullText))
                copied = true
            }, modifier = Modifier.weight(1f)) {
                Text(if (copied) "✅ Скопировано" else "📋 Копировать всё")
            }
            OutlinedButton(onClick = {
                pendingExport = fullText
                exportLauncher.launch("botcontrol-log.txt")
            }, modifier = Modifier.weight(1f)) { Text("💾 Экспорт в .txt") }
        }
        Spacer(Modifier.padding(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "BotControl — общий лог")
                    .putExtra(Intent.EXTRA_TEXT, fullText)
                runCatching {
                    context.startActivity(Intent.createChooser(send, "Отправить лог"))
                }
            }, modifier = Modifier.weight(1f)) { Text("📤 Поделиться") }
            OutlinedButton(onClick = {
                DeviceLlm.log("🧺 Общий лог очищен")
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
                    Text("Пока пусто. Запусти бота — события появятся здесь.",
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
