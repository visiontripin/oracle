package com.botcontrol.admin.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.botcontrol.admin.data.LocalBotStore
import com.botcontrol.admin.data.telegram.TelegramApi
import com.botcontrol.admin.ui.components.ErrorText
import kotlinx.coroutines.launch

/**
 * Step-by-step setup of the on-phone bot: paste the token from @BotFather,
 * we validate it immediately and remember it (encrypted storage).
 */
@Composable
fun LocalSetupScreen(
    localStore: LocalBotStore,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var botName by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrDefault("?")
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onCancel) { Text("← Назад") }
        }
        Text("Новый бот", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Text("Токен твоего бота", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("1. Открой в Telegram бота @BotFather",
                    style = MaterialTheme.typography.bodyMedium)
                Text("2. Отправь команду /newbot и следуй подсказкам",
                    style = MaterialTheme.typography.bodyMedium)
                Text("3. Скопируй токен вида 123456:AAHfqT… и вставь ниже",
                    style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            token, { token = it },
            label = { Text("Токен бота") },
            placeholder = { Text("123456789:AAHfqT…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth(),
        )
        if (error.isNotBlank()) ErrorText(error)
        if (botName.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text("✅ Найден бот: @$botName",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                scope.launch {
                    checking = true; error = ""; botName = ""
                    // Extract a token-shaped string from anything pasted
                    // (handles extra spaces, quotes, "Bot Token:" prefixes)
                    val clean = Regex("\\d{6,12}:[A-Za-z0-9_-]{25,}")
                        .find(token)?.value.orEmpty()
                    if (clean.isEmpty()) {
                        checking = false
                        error = "В вставленном тексте нет токена. Формат: 123456789:AAHfqT… — скопируй целиком сообщение с токеном от @BotFather."
                        return@launch
                    }
                    val result = TelegramApi(clean).validateToken()
                    result.onSuccess { username ->
                        botName = username
                        localStore.addProfile(username.ifBlank { "Новый бот" }, clean, username)
                        localStore.setMode("local")
                    }.onFailure { e -> error = e.message ?: "Не удалось проверить токен" }
                    checking = false
                }
            },
            enabled = !checking && token.trim().length > 20,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (checking) "Проверяем…" else if (botName.isNotBlank()) "Проверить другой токен" else "Проверить токен")
        }

        if (botName.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text("Дальше — запуск бота →")
            }
        }
        // сеть не пустила, но токен похож на настоящий -> можно запустить в фоне
        if (botName.isBlank() && error.contains("Нет связи")) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                scope.launch {
                    val clean = Regex("\\d{6,12}:[A-Za-z0-9_-]{25,}").find(token)?.value.orEmpty()
                    if (clean.isNotBlank()) {
                        localStore.addProfile("Новый бот", clean, "")
                        localStore.setMode("local")
                        onDone()
                    }
                }
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Всё равно сохранить и запустить (бот ждёт сеть сам)")
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("Я лучше подключусь к серверу")
        }
        Spacer(Modifier.height(8.dp))
        Text("BotControl v$version", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
