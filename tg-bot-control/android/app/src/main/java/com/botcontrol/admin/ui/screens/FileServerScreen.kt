package com.botcontrol.admin.ui.screens

import com.botcontrol.admin.ui.components.ConfirmRequest
import com.botcontrol.admin.ui.components.ConfirmHost
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.botcontrol.admin.data.BotFiles
import com.botcontrol.admin.data.BotProfile
import com.botcontrol.admin.data.LocalBotStore
import com.botcontrol.admin.service.FileServer
import java.io.File

/**
 * Файлы бота и сервер хранения: у каждого бота своя директория
 * (media / scripts / docs). Файлы можно импортировать с телефона,
 * а также загружать/скачивать из браузера по Wi-Fi (порт 8090).
 */
@Composable
fun FileServerScreen(
    localStore: LocalBotStore,
    onBack: () -> Unit,
) {
    val confirm = remember { mutableStateOf<ConfirmRequest?>(null) }
    ConfirmHost(confirm)
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var running by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf<FileServer?>(null) }
    var bots by remember { mutableStateOf(listOf<BotProfile>()) }
    var botId by remember { mutableStateOf(1L) }
    var kind by remember { mutableStateOf(BotFiles.MEDIA) }
    var files by remember { mutableStateOf(listOf<File>()) }
    var message by remember { mutableStateOf("") }
    var importKind by remember { mutableStateOf<Pair<Long, String>?>(null) }

    fun refresh() { files = BotFiles.list(context, botId, kind) }

    LaunchedEffect(Unit) {
        val list = localStore.profiles()
        bots = list
        botId = list.firstOrNull()?.id ?: run {
            val ids = BotFiles.allBotIds(context)
            ids.firstOrNull() ?: 1L
        }
        refresh()
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        val target = importKind
        if (uri != null && target != null) {
            val (bid, k) = target
            runCatching {
                val name = queryDisplayName(context, uri)
                    ?: "import_${System.currentTimeMillis() / 1000}.bin"
                val out = BotFiles.safe(context, bid, k, name)
                    ?: BotFiles.subdir(context, bid, k)
                        .resolve("import_${System.currentTimeMillis() / 1000}.bin")
                context.contentResolver.openInputStream(uri)!!.use { ins ->
                    out.outputStream().use { ins.copyTo(it) }
                }
                message = "✅ Файл загружен: ${out.name}"
            }.onFailure { message = "❌ Не удалось импортировать: ${it.message?.take(120)}" }
            botId = bid
            kind = k
            refresh()
        }
        importKind = null
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text("Файлы бота и сервер", style = MaterialTheme.typography.titleLarge)
        }

        // ---------- бот ----------
        Text("Бот:", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            bots.forEach { p ->
                val selected = p.id == botId
                OutlinedButton(
                    onClick = {
                        botId = p.id
                        refresh()
                    },
                    modifier = Modifier.padding(end = 6.dp),
                ) {
                    Text(
                        (if (selected) "● " else "") + (p.name.ifBlank { "Бот ${p.id}" }).take(18),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // ---------- папка ----------
        Text("Папка:", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            BotFiles.kinds.forEach { k ->
                OutlinedButton(
                    onClick = {
                        kind = k
                        refresh()
                    },
                    modifier = Modifier.padding(end = 6.dp),
                ) {
                    Text(
                        (if (k == kind) "● " else "") + when (k) {
                            BotFiles.MEDIA -> "🖼 media"
                            BotFiles.SCRIPTS -> "📜 scripts"
                            else -> "📄 docs"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Spacer(Modifier.padding(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (running) {
                        server?.stop()
                        server = null
                        running = false
                        message = "⏹ Сервер остановлен"
                    } else {
                        runCatching {
                            val s = FileServer(context)
                            s.start()
                            server = s
                            running = true
                            message = "🌐 Сервер запущен: http://${FileServer.lanAddress() ?: "0.0.0.0"}:${FileServer.PORT}"
                        }.onFailure {
                            message = "❌ Не удалось запустить: ${it.message?.take(120)}"
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text(if (running) "⏹ Остановить сервер" else "🌐 Запустить сервер") }
            OutlinedButton(
                onClick = {
                    importKind = botId to kind
                    importLauncher.launch("*/*")
                },
                modifier = Modifier.weight(1f),
            ) { Text("📥 Импорт файла") }
        }

        if (running) {
            Spacer(Modifier.padding(4.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Сервер работает в локальной сети", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "http://${FileServer.lanAddress() ?: "<нет Wi-Fi>"}:${FileServer.PORT}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Открой адрес в браузере компьютера/телефона той же сети — загрузка и скачивание файлов этого бота.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = {
                        clipboard.setText(
                            AnnotatedString("http://${FileServer.lanAddress()}:${FileServer.PORT}"),
                        )
                        message = "📋 Адрес скопирован"
                    }) { Text("Скопировать адрес") }
                }
            }
        }

        if (message.isNotBlank()) {
            Spacer(Modifier.padding(4.dp))
            Text(message, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.padding(6.dp))
        Text(
            "Файлов: ${files.size} (бот $botId, папка $kind)",
            style = MaterialTheme.typography.titleSmall,
        )
        files.forEach { f ->
            Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${f.length() / 1024} КБ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = {
                        val text = runCatching {
                            if (f.length() <= 200_000) f.readText() else null
                        }.getOrNull()
                        if (text != null) {
                            clipboard.setText(AnnotatedString(text))
                            message = "📋 Скопировано: ${f.name}"
                        } else {
                            message = "❌ Файл большой или не текстовый — скопируй через сервер"
                        }
                    }) { Text("📋") }
                    TextButton(onClick = { confirm.value = ConfirmRequest("Удалить файл?", f.name) {
                        f.delete()
                        refresh()
                        message = "🗑 Удалено: ${f.name}"
                    } }) { Text("🗑") }
                }
            }
        }

        Spacer(Modifier.padding(10.dp))
        Text(
            "Медиа можно ссылать в сценариях, скрипты — вставлять в правила «✎ код», документы — держать под рукой. Файлы хранятся внутри приложения отдельно у каждого бота.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.padding(20.dp))
    }
}

private fun queryDisplayName(context: android.content.Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()
