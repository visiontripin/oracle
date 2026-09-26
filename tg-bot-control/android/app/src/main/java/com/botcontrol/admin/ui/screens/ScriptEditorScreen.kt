package com.botcontrol.admin.ui.screens

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
import com.botcontrol.admin.data.BotRepository
import com.botcontrol.admin.llm.BotScript
import com.botcontrol.admin.ui.components.ErrorText
import kotlinx.coroutines.launch

/**
 * Простой редактор кода логики бота (JavaScript, функция handle(e)).
 * Запуск — по кнопке в Telegram; тест — прямо здесь, без Telegram.
 */
@Composable
fun ScriptEditorScreen(repository: BotRepository, ruleId: Int, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var pattern by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var testInput by remember { mutableStateOf("привет") }
    var testOutput by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        repository.botRules().firstOrNull { it.id == ruleId }?.let { rule ->
            code = rule.script.ifBlank { BotScript.EXAMPLE }
            pattern = rule.pattern
            loaded = true
        } ?: run { error = "Правило не найдено" }
    }

    if (!loaded && error.isBlank()) return

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text("Скрипт: $pattern", style = MaterialTheme.typography.titleSmall)
        }
        Text("JS-функция handle(e): e.user, e.text, e.chatId → верни текст ответа. Без доступа к Java/сети.",
            style = MaterialTheme.typography.bodySmall)
        if (error.isNotBlank()) ErrorText(error)
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            code, { code = it },
            label = { Text("Код скрипта") },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace,
                fontSize = MaterialTheme.typography.bodySmall.fontSize),
            modifier = Modifier.fillMaxWidth().height(320.dp),
        )
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { code = BotScript.EXAMPLE }) { Text("Пример") }
            Button(onClick = {
                scope.launch {
                    repository.botRules().firstOrNull { it.id == ruleId }?.let { rule ->
                        repository.saveBotRule(rule.copy(script = code))
                        message = "Сохранено"
                    }
                }
            }) { Text("Сохранить") }
        }

        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Тест скрипта", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(testInput, { testInput = it },
                    label = { Text("Входящее сообщение") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    BotScript.run(code, "Тест", testInput, 0L)
                        .onSuccess { testOutput = it; error = "" }
                        .onFailure { error = it.message ?: "Ошибка" }
                }, modifier = Modifier.fillMaxWidth()) { Text("▶ Выполнить") }
                if (testOutput.isNotBlank()) {
                    Text("Ответ: $testOutput", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
