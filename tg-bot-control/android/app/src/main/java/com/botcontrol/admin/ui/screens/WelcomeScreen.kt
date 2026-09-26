package com.botcontrol.admin.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.botcontrol.admin.data.BotProfile
import com.botcontrol.admin.data.LocalBotStore
import com.botcontrol.admin.service.LocalBotService
import com.botcontrol.admin.ui.components.BrandLogo
import com.botcontrol.admin.ui.components.ProjectLinksCard
import kotlinx.coroutines.launch

/**
 * Главный экран: корни дерева — боты (со статусом), кнопки «+» для
 * добавления бота/сервера, вход в FAQ. Данные последнего сбоя, если был.
 */
@Composable
fun WelcomeScreen(
    localStore: LocalBotStore,
    onAddBot: () -> Unit,
    onServer: () -> Unit,
    onOpenBot: (Long) -> Unit,
    onFaq: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val states by LocalBotService.states.collectAsState()

    var profiles by remember { mutableStateOf<List<BotProfile>>(emptyList()) }
    var showReplaceWarn by remember { mutableStateOf(false) }
    var lastCrash by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        profiles = localStore.profiles()
        lastCrash = com.botcontrol.admin.CrashLog.last(context)
    }

    Column(
        Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            BrandLogo(48.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Мои боты", style = MaterialTheme.typography.headlineMedium)
                Text("BotControl — боты с ИИ прямо на телефоне",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = { showReplaceWarn = true }) { Text("+") }
        }
        Spacer(Modifier.height(16.dp))

        if (profiles.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Ботов пока нет", style = MaterialTheme.typography.titleMedium)
                    Text("Нажми «+» или «Добавить бота» — займёт около двух минут: вставь токен от @BotFather и готово.",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // ---------- корни дерева: по карточке на бота ----------
        profiles.forEach { profile ->
            val st = states[profile.id]
            Card(
                Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .clickable { onOpenBot(profile.id) },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (st?.running == true) "🟢" else "⚪️",
                            style = MaterialTheme.typography.titleLarge)
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(profile.name.ifBlank { "Бот ${profile.id}" },
                                style = MaterialTheme.typography.titleMedium)
                            val uname = profile.username
                            if (uname.isNotBlank()) Text("@$uname",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Text("▾", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(6.dp))
                    // Проблемы висят, пока не устранены (меню, сеть, конфликт, ИИ…).
                    st?.problems?.forEach { (tag, text) ->
                        Text("⚠️ $text",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (st?.problems?.isNotEmpty() != true && st?.lastError?.isNotBlank() == true) {
                        Text("⚠️ ${st.lastError}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch { localStore.setActiveBot(profile.id) }
                            LocalBotService.startBot(context, profile.id)
                        }, enabled = st?.running != true, modifier = Modifier.weight(1f)) {
                            Text("▶ Запустить")
                        }
                        OutlinedButton(onClick = {
                            LocalBotService.stopBot(context, profile.id)
                        }, enabled = st?.running == true, modifier = Modifier.weight(1f)) {
                            Text("■ Остановить")
                        }
                    }
                }
            }
        }

        // ---------- добавить ----------
        Button(onClick = { showReplaceWarn = true }, modifier = Modifier.fillMaxWidth()) {
            Text("+ Добавить бота")
        }
        Text("Можно добавить несколько ботов — каждый на своём токене, работают одновременно. Правила, расписание и характер у каждого свои.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onServer, modifier = Modifier.fillMaxWidth()) {
            Text("+ Добавить сервер")
        }
        Text("Необязательно. Сервер нужен, только если у тебя уже есть свой бот на ПК/VPS с нашей серверной частью — тогда приложение станет пультом к нему. Ботам на телефоне сервер не требуется.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (lastCrash.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Text("⚠️ В прошлый раз приложение упало:",
                        style = MaterialTheme.typography.titleSmall)
                    Text(lastCrash.take(300), style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = {
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", lastCrash))
                        }) { Text("Скопировать") }
                        TextButton(onClick = {
                            com.botcontrol.admin.CrashLog.clear(context); lastCrash = ""
                        }) { Text("Скрыть") }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { showReplaceWarn = true }, modifier = Modifier.fillMaxWidth()) {
            Text("+ Добавить ещё одного бота")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onFaq, modifier = Modifier.fillMaxWidth()) {
            Text("❓ Как пользоваться — FAQ")
        }
        Spacer(Modifier.height(12.dp))
        ProjectLinksCard()
        Spacer(Modifier.height(24.dp))
    }

    if (showReplaceWarn) {
        AlertDialog(
            onDismissRequest = { showReplaceWarn = false },
            title = { Text("Что добавить?") },
            text = {
                Text("Бот — работает на этом телефоне по своему токену (можно несколько). Сервер — подключение к своему ПК/VPS с серверной частью BotControl, нужен логин и пароль.")
            },
            confirmButton = {
                TextButton(onClick = { showReplaceWarn = false; onAddBot() }) { Text("🤖 Бота") }
            },
            dismissButton = {
                TextButton(onClick = { showReplaceWarn = false; onServer() }) { Text("🖥 Сервер") }
            },
        )
    }
}
