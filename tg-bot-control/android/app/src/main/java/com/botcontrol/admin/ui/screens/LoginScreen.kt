package com.botcontrol.admin.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.botcontrol.admin.data.AuthStore
import com.botcontrol.admin.data.BotRepository
import com.botcontrol.admin.data.LanScanner
import com.botcontrol.admin.ui.components.ErrorText
import kotlinx.coroutines.launch

private val PRESETS = listOf(
    "Wi-Fi дома" to "http://192.168.1.XXX:8000",
    "Tailscale" to "http://имя-сервера:8000",
    "Cloudflare" to "https://xxx.trycloudflare.com",
    "VPS / домен" to "https://bot.example.com",
)

@Composable
fun LoginScreen(
    authStore: AuthStore,
    repository: BotRepository,
    localStore: com.botcontrol.admin.data.LocalBotStore? = null,
    onLoggedIn: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var baseUrl by remember { mutableStateOf("http://192.168.1.XXX:8000") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<String>?>(null) }
    var scanning by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
    ) {
        if (onBack != null) {
            TextButton(onClick = onBack) { Text("← Назад") }
        }
        Text("Подключение к серверу", style = MaterialTheme.typography.headlineSmall)
        Text("Где адрес взять — подсказки под полем", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            baseUrl, { baseUrl = it }, label = { Text("Адрес сервера") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            PRESETS.forEach { (name, url) ->
                FilterChip(selected = baseUrl == url, onClick = {
                    baseUrl = url
                    hint = when (name) {
                        "Wi-Fi дома" -> "IP сервера смотри на нём командой «ip a» (или «ipconfig» в Windows)"
                        "Tailscale" -> "Имя узла — команда «tailscale status» на сервере"
                        "Cloudflare" -> "URL выдаёт скрипт scripts/tunnel_cloudflared.sh на сервере"
                        else -> "Домен своего сервера + https"
                    }
                }, label = { Text(name) })
            }
        }
        if (hint.isNotBlank()) {
            Text(hint, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(
            onClick = {
                scope.launch {
                    scanning = true; found = null; error = ""
                    val result = LanScanner.scan(context)
                    scanning = false
                    if (result.isEmpty()) error = "Сервер в этой Wi-Fi сети не найден. Убедись, что он запущен и телефон в той же сети."
                    else found = result
                }
            },
            enabled = !scanning,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (scanning) "Ищем сервер…" else "🔍 Найти сервер в этой Wi-Fi сети") }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            username, { username = it }, label = { Text("Логин") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            password, { password = it }, label = { Text("Пароль") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        if (error.isNotBlank()) ErrorText(error)
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                scope.launch {
                    busy = true
                    error = ""
                    val res = repository.login(baseUrl.trim(), username.trim(), password)
                    res.onSuccess { body ->
                        authStore.saveSession(body.accessToken, username.trim(), body.role, baseUrl.trim())
                        repository.updateBaseUrl(baseUrl.trim())
                        localStore?.setMode("server")
                        busy = false
                        onLoggedIn()
                    }.onFailure {
                        error = it.message ?: "Не удалось подключиться"
                        busy = false
                    }
                }
            },
            enabled = !busy && username.isNotBlank() && password.isNotBlank() && !baseUrl.contains("XXX"),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (busy) "Подключаемся…" else "Войти")
        }
    }

    found?.let { list ->
        AlertDialog(
            onDismissRequest = { found = null },
            title = { Text("Найдены серверы") },
            text = {
                Column {
                    list.forEach { url ->
                        TextButton(onClick = { baseUrl = url; found = null }) { Text(url) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { found = null }) { Text("Закрыть") } },
        )
    }
}
