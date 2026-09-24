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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import com.botcontrol.admin.data.LocalBotStore
import com.botcontrol.admin.ui.components.DropdownField
import com.botcontrol.admin.ui.components.SectionTitle
import kotlinx.coroutines.launch

/**
 * Поведение ответов бота — то, что видно собеседнику:
 * имитация набора «печатает…», пауза между ответами (антифлуд),
 * память диалога, уточняющие вопросы при сбое ИИ, запасной ответ.
 */
@Composable
fun BehaviorScreen(localStore: LocalBotStore, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var botId by remember { mutableStateOf(0L) }
    var typing by remember { mutableStateOf(4) }
    var cooldown by remember { mutableStateOf(10) }
    var historyLimit by remember { mutableStateOf(4) }
    var clarifyOn by remember { mutableStateOf(true) }
    var clarify by remember { mutableStateOf<List<String>>(emptyList()) }
    var defaultReply by remember { mutableStateOf("") }
    var newClarify by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        botId = localStore.activeBotId()
        typing = localStore.typingSeconds(botId)
        cooldown = localStore.cooldownSec(botId)
        historyLimit = localStore.historyLimit(botId)
        clarifyOn = localStore.clarifyEnabled(botId)
        clarify = localStore.clarifyQuestions(botId)
        defaultReply = localStore.defaultReply(botId)
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text("Поведение", style = MaterialTheme.typography.titleLarge)
        }
        Text("Применяется ко всем ответам этого бота: правила, наборы, напоминания, ИИ.",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))

        SectionTitle("Имитация набора текста")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            DropdownField(
                value = typing,
                options = listOf(0, 1, 2, 3, 4, 5),
                label = "«Печатает…» перед ответом",
                display = { if (it == 0) "выкл" else "$it сек" },
                onSelect = {
                    typing = it
                    scope.launch { localStore.setTypingSeconds(it, botId) }
                },
                modifier = Modifier.weight(1f),
            )
            DropdownField(
                value = cooldown,
                options = listOf(0, 5, 10, 15, 30, 60),
                label = "Пауза между ответами",
                display = { if (it == 0) "нет" else "$it сек" },
                onSelect = {
                    cooldown = it
                    scope.launch { localStore.setCooldownSec(it, botId) }
                },
                modifier = Modifier.weight(1f),
            )
        }
        Text("«Печатает…» показывает, что бот готовит ответ (как typing-плагин в python-боте). Пауза защищает от флуда: на незнакомые сообщения бот отвечает не чаще раза в N секунд.",
            style = MaterialTheme.typography.bodySmall)

        SectionTitle("Память диалога (контекст ИИ)")
        DropdownField(
            value = historyLimit,
            options = listOf(0, 2, 4, 6, 8, 10),
            label = "Помнить последних реплик",
            display = { if (it == 0) "0 — не помнить" else "$it" },
            onSelect = {
                historyLimit = it
                scope.launch { localStore.setHistoryLimit(it, botId) }
            },
        )

        SectionTitle("Если ИИ недоступен")
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Уточняющие вопросы")
                    Text("Случайное из списка: «а что?», «зачем?»…",
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(clarifyOn, onCheckedChange = {
                    clarifyOn = it
                    scope.launch { localStore.setClarifyEnabled(it, botId) }
                })
            }
        }
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            defaultReply, { defaultReply = it },
            label = { Text("Запасной ответ, если уточняющие выключены") },
            modifier = Modifier.fillMaxWidth(),
        )
        clarify.forEach { question ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("• $question", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    scope.launch {
                        localStore.setClarifyQuestions(clarify - question, botId)
                        clarify = clarify - question
                    }
                }) { Text("✕") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(newClarify, { newClarify = it },
                label = { Text("Свой вопрос") },
                modifier = Modifier.weight(1f))
            TextButton(onClick = {
                if (newClarify.isBlank()) return@TextButton
                scope.launch {
                    localStore.setClarifyQuestions(clarify + newClarify.trim(), botId)
                    clarify = clarify + newClarify.trim()
                    newClarify = ""
                }
            }) { Text("+") }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = {
            scope.launch {
                localStore.setDefaultReply(defaultReply, botId)
            }
        }) { Text("💾 Сохранить запасной ответ") }

        Spacer(Modifier.height(24.dp))
    }
}
