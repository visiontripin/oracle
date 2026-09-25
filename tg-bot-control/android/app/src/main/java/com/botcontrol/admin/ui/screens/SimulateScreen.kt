package com.botcontrol.admin.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.botcontrol.admin.service.BotDecision
import com.botcontrol.admin.data.BotRepository
import com.botcontrol.admin.data.LocalBotStore
import com.botcontrol.admin.llm.ChatMemory
import com.botcontrol.admin.llm.DeviceLlm
import com.botcontrol.admin.service.BotBrain
import kotlinx.coroutines.launch

private data class SimResult(
    val input: String,
    val source: String,
    val reply: String,
    val menu: List<String>,
    val trace: List<String>,
    val anim: com.botcontrol.admin.data.AnimSpec? = null,
    val animPack: List<String> = emptyList(),
    val dice: String = "",
)

/**
 * Имитация поведения бота: проверка логики без Telegram. Использует тот же
 * «мозг» (BotBrain), что и реальный бот: правила (код) → сценарий ИИ
 * (/chat, правило «ИИ») → антифлуд → кодовый запасной ответ.
 */
@Composable
fun SimulateScreen(
    repository: BotRepository,
    localStore: LocalBotStore,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val busy by DeviceLlm.busy.collectAsState()

    var input by remember { mutableStateOf("привет") }
    var running by remember { mutableStateOf(false) }
    val results = remember { mutableStateListOf<SimResult>() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text("Имитация бота", style = MaterialTheme.typography.titleLarge)
        }
        Text("Введи сообщение — увидишь ответ бота и его «раздумья»: какое правило сработало, вызван ли ИИ, какие кнопки уйдут в Telegram. Логика та же, что у настоящего бота.",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                input, { input = it },
                label = { Text("Сообщение от пользователя") },
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    val message = input.trim()
                    if (message.isBlank() || running) return@Button
                    running = true
                    val trace = mutableListOf<String>()
                    scope.launch {
                        try {
                            // Имитация — «свежая голова»: без чужого контекста и кулдауна.
                            ChatMemory.clear(-2L, -1L)
                            val simBotId = localStore.activeBotId()
                            trace.add("Настройки поведения: «печатает» ${localStore.typingSeconds(simBotId)} с, " +
                                "пауза ${localStore.cooldownSec(simBotId)} с, память ${localStore.historyLimit(simBotId)} реплик")
                            val decision: BotDecision = BotBrain.decide(
                                context = context,
                                store = localStore,
                                botId = localStore.activeBotId(),
                                chatId = -1L,
                                firstName = "Тестер",
                                text = message,
                                respectCooldown = false,
                                trace = { trace.add(it) },
                            )
                            val animPack = decision.anim?.packId?.takeIf { it.isNotBlank() }?.let { id ->
                                localStore.packs().firstOrNull { it.id == id }?.items
                            }.orEmpty()
                            results.add(0, SimResult(
                                input = message,
                                source = decision.source,
                                reply = decision.reply,
                                menu = decision.menu.map { it.label },
                                trace = trace.toList(),
                                anim = decision.anim,
                                animPack = animPack,
                                dice = decision.dice,
                            ))
                        } finally {
                            running = false
                        }
                    }
                },
                enabled = !running && input.isNotBlank() && !busy,
            ) { Text(if (running || busy) "…" else "▶ Проверить") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { input = "привет" }) { Text("привет") }
            OutlinedButton(onClick = { input = "/start" }) { Text("/start") }
            OutlinedButton(onClick = { input = "/chat как дела?" }) { Text("🤖 /chat") }
            OutlinedButton(onClick = { results.clear() }) { Text("Очистить") }
        }
        Spacer(Modifier.height(8.dp))

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            results.forEach { res ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Юзер: ${res.input}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text("Источник: ${res.source}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(4.dp))
                        when {
                            res.anim != null ->
                                com.botcontrol.admin.ui.components.AnimPreview(res.anim, res.animPack)
                            res.dice.isNotBlank() ->
                                Text("${res.dice}  (кубик Telegram: результат случайный)",
                                    style = MaterialTheme.typography.bodyMedium)
                            else -> Text(res.reply.ifBlank { "(промолчал — кулдаун)" },
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        if (res.menu.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text("Кнопки: ${res.menu.joinToString(" | ")}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Раздумья:", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        res.trace.forEach { step ->
                            Text("• $step", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
