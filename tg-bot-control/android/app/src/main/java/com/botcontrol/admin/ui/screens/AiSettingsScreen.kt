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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.botcontrol.admin.data.LocalBotStore
import com.botcontrol.admin.data.PerkurPresets
import com.botcontrol.admin.llm.DeviceLlm
import com.botcontrol.admin.ui.components.DropdownField
import com.botcontrol.admin.ui.components.SectionTitle
import kotlinx.coroutines.launch

/**
 * Все параметры разговорного режима ИИ — выпадающими списками:
 * температура, top-K, длина ответа, память диалога, «печатает…», кулдаун,
 * уточняющие вопросы при недоступном ИИ.
 */
@Composable
fun AiSettingsScreen(localStore: LocalBotStore, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var botId by remember { mutableStateOf(0L) }

    var temperature by remember { mutableStateOf(0.8f) }
    var topK by remember { mutableStateOf(40) }
    var maxTokens by remember { mutableStateOf(1024) }
    var showSmith by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        botId = localStore.activeBotId()
        temperature = localStore.aiTemperature(botId)
        topK = localStore.aiTopK(botId)
        maxTokens = localStore.aiMaxTokens(botId)
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text("Настройки ИИ", style = MaterialTheme.typography.titleLarge)
        }

        SectionTitle("Стиль генерации")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            DropdownField(
                value = temperature,
                options = listOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f, 1.2f, 1.5f),
                label = "Температура",
                display = { "%.1f".format(it) },
                onSelect = {
                    temperature = it
                    scope.launch { localStore.setAiTemperature(it, botId) }
                },
                modifier = Modifier.weight(1f),
            )
            DropdownField(
                value = topK,
                options = listOf(1, 5, 10, 20, 30, 40, 50, 64),
                label = "Top-K",
                display = { "$it" },
                onSelect = {
                    topK = it
                    topK = it
                    scope.launch { localStore.setAiTopK(it, botId) }
                },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        DropdownField(
            value = maxTokens,
            options = listOf(64, 80, 128, 256, 512, 1024),
            label = "Максимум токенов в ответе",
            display = { "$it" },
            onSelect = {
                maxTokens = it
                scope.launch { localStore.setAiMaxTokens(it, botId) }
            },
        )
        Text("Новое значение применяется после перезагрузки модели («Выгрузить» во вкладке «Чат с ИИ»). Для движка LiteRT-LM действует лимит длины; температура/top-K — для движка MediaPipe (.task/.bin).",
            style = MaterialTheme.typography.bodySmall)

        Text("Паузы «печатает…», антифлуд, память диалога и уточняющие вопросы — в разделе «Сценарии → Поведение и паузы».",
            style = MaterialTheme.typography.bodySmall)

        SectionTitle("Готовый характер")
        OutlinedButton(onClick = { showSmith = true }, modifier = Modifier.fillMaxWidth()) {
            Text("🕶 Поставить характер «Агент Смит»")
        }
        Text("Меняется в поле «Характер бота» на главном экране бота.",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))
    }

    if (showSmith) {
        AlertDialog(
            onDismissRequest = { showSmith = false },
            title = { Text("Характер «Агент Смит»") },
            text = { Text("Текущий системный промт будет заменён промтом Агента Смита из Матрицы (холодный, саркастичный, только русский, коротко).") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        localStore.setSystemPrompt(PerkurPresets.SMITH_PROMPT, botId)
                        DeviceLlm.log("🕶 Установлен характер «Агент Смит»")
                        showSmith = false
                    }
                }) { Text("Поставить") }
            },
            dismissButton = { TextButton(onClick = { showSmith = false }) { Text("Отмена") } },
        )
    }
}
