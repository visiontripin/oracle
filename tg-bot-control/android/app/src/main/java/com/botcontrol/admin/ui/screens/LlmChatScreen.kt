package com.botcontrol.admin.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.botcontrol.admin.data.LocalBotStore
import com.botcontrol.admin.llm.DeviceLlm
import com.botcontrol.admin.llm.DevicePrefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * «Чат с ИИ» — direct conversation with the on-device model plus a live
 * «процесс раздумий» panel (model loading steps, requests, the model's
 * thinking channel, errors). Same engine instance the bot itself uses.
 */
@Composable
fun LlmChatScreen(localStore: LocalBotStore, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var botId by remember { mutableStateOf(0L) }
    var modelName by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        botId = localStore.activeBotId()
        modelName = localStore.aiModel(botId)
    }
    suspend fun botParams(): com.botcontrol.admin.llm.EngineParams =
        com.botcontrol.admin.llm.EngineParams(
            temperature = localStore.aiTemperature(botId),
            topK = localStore.aiTopK(botId),
            maxTokens = localStore.aiMaxTokens(botId),
        )

    val logEntries by DeviceLlm.log.collectAsState()
    val chatHistory = remember {
        mutableStateListOf<Pair<String, String>>()
    }
    val busy by DeviceLmBusy()

    var messages by remember { mutableStateOf(listOf<Pair<Boolean, String>>()) }
    var input by remember { mutableStateOf("") }
    var systemPrompt by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var showThinking by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) { systemPrompt = localStore.systemPrompt() }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Чат с ИИ", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("Готово") }
        }
        Text(
            "Модель: ${modelName.ifBlank { "не выбрана" }}" +
                (if (DeviceLlm.loadedModelName != null) " • загружена" else " • не в памяти"),
            style = MaterialTheme.typography.bodySmall,
        )
        if (error.isNotBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                scope.launch {
                    error = ""
                    if (modelName.isBlank()) {
                        error = "Сначала выбери модель во вкладке «Модели ИИ»"
                    } else {
                        DeviceLlm.ensureLoaded(context, modelName, botParams())
                            .onFailure { err -> error = err.message ?: "Ошибка загрузки" }
                    }
                }
            }, enabled = !busy) { Text("Загрузить модель") }
            OutlinedButton(onClick = { DeviceLlm.unload() }, enabled = !busy) {
                Text("Выгрузить")
            }
            OutlinedButton(onClick = {
                messages = emptyList(); chatHistory.clear()
                com.botcontrol.admin.llm.ChatMemory.clear(-2L, 0L); DeviceLlm.log("🧺 Чат и контекст очищены")
            }) { Text("Очистить чат") }
        }

        // ---- «процесс раздумий» ----
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { showThinking = !showThinking }) {
                Text(if (showThinking) "▼ Процесс раздумий" else "▶ Процесс раздумий")
            }
            if (busy) Text("⏳ модель работает…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
        }
        if (showThinking) {
            Card(
                Modifier.fillMaxWidth().height(170.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                val listState = rememberLazyListState()
                LaunchedEffect(logEntries.size) {
                    if (logEntries.isNotEmpty()) listState.animateScrollToItem(logEntries.size - 1)
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    items(logEntries) { entry ->
                        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                            .format(Date(entry.epochSec * 1000))
                        Text(
                            "$time  ${entry.text}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ---- chat ----
        Spacer(Modifier.height(8.dp))
        val chatState = rememberLazyListState()
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) chatState.animateScrollToItem(messages.size - 1)
        }
        LazyColumn(state = chatState, modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(messages) { (isUser, text) ->
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.widthIn(max = 320.dp),
                    ) {
                        Text(text, Modifier.padding(10.dp))
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                input, { input = it },
                label = { Text("Сообщение модели") },
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    val question = input.trim()
                    input = ""
                    error = ""
                    messages = messages + (true to question)
                    scope.launch {
                        if (modelName.isBlank()) {
                            error = "Модель не выбрана — вкладка «Модели ИИ»"
                            return@launch
                        }
                        DeviceLlm.ensureLoaded(context, modelName, botParams())
                            .mapCatching {
                                val prompt = com.botcontrol.admin.llm.DialogPrompt.build(
                                    systemPrompt, chatHistory, question)
                                DeviceLlm.generate("", prompt).getOrThrow()
                            }
                            .onSuccess { reply ->
                                chatHistory.add(question to reply)
                                val limit = runCatching { localStore.historyLimit() }.getOrDefault(4)
                                while (chatHistory.size > limit) chatHistory.removeAt(0)
                                messages = messages + (false to reply)
                            }
                            .onFailure { err -> error = err.message ?: "Ошибка генерации" }
                    }
                },
                enabled = !busy && input.isNotBlank(),
            ) { Text(if (busy) "…" else "➤") }
        }
        OutlinedTextField(
            systemPrompt, { systemPrompt = it },
            label = { Text("Характер бота (системный промт)") },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(70.dp),
        )
        TextButton(onClick = {
            scope.launch { localStore.setSystemPrompt(systemPrompt); DeviceLlm.log("💾 Характер сохранён") }
        }) { Text("Сохранить характер") }
        Spacer(Modifier.height(4.dp))
    }
}

/** Collects DeviceLlm.busy as Compose state. */
@Composable
private fun DeviceLmBusy(): androidx.compose.runtime.State<Boolean> =
    DeviceLlm.busy.collectAsState()
