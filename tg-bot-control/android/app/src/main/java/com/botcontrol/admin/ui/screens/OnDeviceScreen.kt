package com.botcontrol.admin.ui.screens

import com.botcontrol.admin.ui.components.ConfirmRequest
import com.botcontrol.admin.ui.components.ConfirmHost
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.botcontrol.admin.data.BotRepository
import com.botcontrol.admin.data.remote.LlmProfileDto
import com.botcontrol.admin.llm.DeviceLlm
import com.botcontrol.admin.llm.DeviceModelsStore
import com.botcontrol.admin.llm.DevicePrefs
import com.botcontrol.admin.llm.ModelCatalog
import com.botcontrol.admin.llm.ModelDownloader
import com.botcontrol.admin.service.LlmAgentService
import com.botcontrol.admin.ui.components.ErrorText
import com.botcontrol.admin.ui.components.SectionTitle
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope

@Composable
fun OnDeviceScreen(
    repository: BotRepository,
    onBack: () -> Unit,
    localMode: Boolean = false,
    localStore: com.botcontrol.admin.data.LocalBotStore? = null,
) {
    val confirm = remember { mutableStateOf<ConfirmRequest?>(null) }
    ConfirmHost(confirm)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val store = remember { DeviceModelsStore(context) }
    val prefs = remember { DevicePrefs(context) }

    // Выбор модели пишем и активному боту (изоляция настроек), и глобально (совместимость).
    fun selectModel(name: String) {
        prefs.selectedModel = name
        val ls = localStore ?: return
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            ls.setAiModel(name, ls.activeBotId())
        }
    }

    var models by remember { mutableStateOf(store.list()) }
    var selected by remember { mutableStateOf(prefs.selectedModel) }
    var agentRunning by remember { mutableStateOf(LlmAgentService.isRunning) }
    var agentOnline by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(0) }

    var temperature by remember { mutableStateOf(prefs.temperature) }
    var topK by remember { mutableStateOf(prefs.topK.toFloat()) }
    var maxTokens by remember { mutableStateOf(prefs.maxTokens.toFloat()) }
    var seedText by remember { mutableStateOf(if (prefs.seed >= 0) prefs.seed.toString() else "") }

    var systemPrompt by remember { mutableStateOf("") }
    var question by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    var showDownload by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf<Float?>(null) }

    fun refreshAgent() {
        scope.launch {
            repository.llmAgentStatus().onSuccess {
                agentOnline = it.online; pending = it.pending
            }
            agentRunning = LlmAgentService.isRunning
        }
    }

    LaunchedEffect(Unit) {
        refreshAgent()
        repository.llmProfile().onSuccess { systemPrompt = it.systemPrompt }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true; error = ""; message = ""
                try {
                    val name = uri.lastPathSegment?.substringAfterLast('/')
                        ?.takeIf { it.isNotBlank() } ?: "imported-${System.currentTimeMillis()}.task"
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val saved = store.importFrom(stream, name)
                        models = store.list()
                        selected = saved.name
                        selectModel(saved.name)
                        message = "Импортирован: ${saved.name} (${saved.sizeBytes / 1024 / 1024} MiB)"
                    } ?: run { error = "Не удалось открыть файл" }
                } catch (e: Exception) {
                    error = e.message ?: "Import failed"
                }
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("On-device LLM", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = onBack) { Text("Done") }
        }
        Text("Модель работает на этом телефоне — без облачных API и без Termux.",
            style = MaterialTheme.typography.bodySmall)
        if (error.isNotBlank()) ErrorText(error)
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)

        // ---- agent ----
        if (!localMode) {
        SectionTitle("Агент (ответы бота с телефона)")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(if (agentRunning) "Сервис: ЗАПУЩЕН" else "Сервис: остановлен",
                    color = if (agentRunning) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error)
                Text(if (agentOnline) "Сервер видит агента онлайн" else "Сервер не видит агента",
                    style = MaterialTheme.typography.bodySmall)
                Text("Заданий в очереди сервера: $pending", style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                LlmAgentService.start(context)
                agentRunning = true
                scope.launch { delay600(); refreshAgent() }
            }, enabled = !agentRunning) { Text("Start agent") }
            OutlinedButton(onClick = {
                LlmAgentService.stop(context)
                agentRunning = false
                refreshAgent()
            }, enabled = agentRunning) { Text("Stop agent") }
        }
        }

        // ---- models ----
        SectionTitle("Модели на устройстве")
        if (models.isEmpty()) {
            Text("Пока ничего не скачано.", style = MaterialTheme.typography.bodySmall)
        }
        models.forEach { m ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected == m.name, onClick = {
                        selected = m.name
                        selectModel(m.name)
                    })
                    Column(Modifier.weight(1f)) {
                        Text(m.name, style = MaterialTheme.typography.titleSmall)
                        Text("${m.sizeBytes / 1024 / 1024} MiB",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { confirm.value = ConfirmRequest("Удалить модель?", "${m.name} — файл удалится с телефона, для работы ИИ его придётся скачать заново.") {
                        store.delete(m.name)
                        models = store.list()
                        if (selected == m.name) { selected = ""; selectModel("") }
                    } }) { Text("✕") }
                }
            }
        }
        if (downloadProgress != null) {
            Text("Загрузка: ${(downloadProgress!! * 100).toInt()}%",
                color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { importLauncher.launch("*/*") }, enabled = !busy) {
                Text("Импорт файла")
            }
            OutlinedButton(onClick = { showDownload = true }, enabled = !busy) {
                Text("Скачать по URL")
            }
        }

        // ---- catalog ----
        SectionTitle("Каталог (принять лицензию → получить ссылку)")
        ModelCatalog.entries.forEach { e ->
            Card(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Column(Modifier.padding(8.dp)) {
                    Text(e.name, style = MaterialTheme.typography.titleSmall)
                    Text("${e.paramsHint} • ${e.sizeHint}", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { uriHandler.openUri(e.infoUrl) }) { Text("Открыть страницу") }
                }
            }
        }

        // ---- params ----
        SectionTitle("Параметры генерации (применяются при загрузке модели)")
        Text("temperature: ${"%.2f".format(temperature)}")
        Slider(temperature, { temperature = it }, valueRange = 0f..2f, steps = 39)
        Text("topK: ${topK.toInt()}")
        Slider(topK, { topK = it }, valueRange = 1f..64f)
        Text("max_tokens: ${maxTokens.toInt()}")
        Slider(maxTokens, { maxTokens = it }, valueRange = 128f..4096f)
        OutlinedTextField(seedText, { seedText = it }, label = { Text("Seed (-1 = случайный)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Button(onClick = {
            prefs.temperature = temperature
            prefs.topK = topK.toInt()
            prefs.maxTokens = maxTokens.toInt()
            prefs.seed = seedText.trim().toIntOrNull() ?: -1
            message = "Параметры сохранены"
        }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить параметры") }

        // ---- system prompt (kept on server profile; agent applies it per job) ----
        if (!localMode) {
        SectionTitle("Системный промт (серверный профиль)")
        OutlinedTextField(systemPrompt, { systemPrompt = it },
            modifier = Modifier.fillMaxWidth().height(110.dp))
        Spacer(Modifier.height(4.dp))
        Button(onClick = {
            scope.launch {
                busy = true
                repository.llmProfile().onSuccess { current ->
                    repository.llmUpdateProfile(
                        LlmProfileDto(
                            systemPrompt = systemPrompt,
                            temperature = current.temperature, topP = current.topP,
                            maxTokens = current.maxTokens, seed = current.seed,
                            timeoutSec = current.timeoutSec, contextChars = current.contextChars,
                        )
                    ).onSuccess { message = "Промт сохранён на сервере" }
                        .onFailure { error = it.message ?: "Save failed" }
                }
                busy = false
            }
        }, modifier = Modifier.fillMaxWidth(), enabled = !busy) { Text("Сохранить промт") }
        }

        // ---- local test chat ----
        SectionTitle("Локальный тест модели")
        OutlinedTextField(question, { question = it }, label = { Text("Сообщение") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Button(onClick = {
            scope.launch {
                busy = true; error = ""; answer = ""
                if (selected.isBlank()) {
                    error = "Сначала выберите модель"
                } else {
                    DeviceLlm.ensureLoaded(context, selected, prefs.toParams())
                        .mapCatching { DeviceLlm.generate(systemPrompt, question).getOrThrow() }
                        .onSuccess { answer = it }
                        .onFailure { error = it.message ?: "Generation failed" }
                }
                busy = false
            }
        }, enabled = !busy && question.isNotBlank() && selected.isNotBlank(),
            modifier = Modifier.fillMaxWidth()) { Text("Сгенерировать локально") }
        if (answer.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Card(Modifier.fillMaxWidth()) { Text(answer, Modifier.padding(12.dp)) }
        }
        if (DeviceLlm.loadedModelName != null) {
            TextButton(onClick = { DeviceLlm.unload() }) { Text("Выгрузить модель из памяти") }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showDownload) {
        var url by remember { mutableStateOf("") }
        var token by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (downloadProgress == null) showDownload = false },
            title = { Text("Скачать модель") },
            text = {
                Column {
                    OutlinedTextField(url, { url = it },
                        label = { Text("Прямой URL (.task / .bin)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(token, { token = it },
                        label = { Text("Bearer-токен (для HF/Kaggle, необязательно)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (downloadProgress != null) {
                        Text("Прогресс: ${(downloadProgress!! * 100).toInt()}%",
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = store.targetFile(url.substringAfterLast('/').ifBlank { "model.task" })
                        scope.launch {
                            busy = true; downloadProgress = 0f; error = ""
                            ModelDownloader().download(url.trim(), target,
                                token.trim().ifBlank { null }) { done, total ->
                                if (total > 0) downloadProgress = done.toFloat() / total
                            }.onSuccess { f ->
                                models = store.list()
                                selected = f.name
                                selectModel(f.name)
                                message = "Скачано: ${f.name}"
                                showDownload = false
                            }.onFailure { error = it.message ?: "Download failed" }
                            downloadProgress = null
                            busy = false
                        }
                    },
                    enabled = url.isNotBlank() && downloadProgress == null,
                ) { Text("Скачать") }
            },
            dismissButton = {
                TextButton(onClick = { if (downloadProgress == null) showDownload = false }) {
                    Text("Закрыть")
                }
            },
        )
    }
}

private suspend fun delay600() = kotlinx.coroutines.delay(600)
