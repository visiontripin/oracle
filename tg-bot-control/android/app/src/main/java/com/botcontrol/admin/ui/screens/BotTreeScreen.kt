package com.botcontrol.admin.ui.screens

import androidx.compose.animation.animateContentSize
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import com.botcontrol.admin.data.BotProfile
import com.botcontrol.admin.data.BotRepository
import com.botcontrol.admin.data.LocalBotStore
import com.botcontrol.admin.service.LocalBotService
import kotlinx.coroutines.launch

/**
 * Дерево бота по наброску: корень = имя бота, подпапки —
 * скрипты, кнопки, ИИ, сценарии, тест, меню. «+» на главном экране
 * добавляет бота; в правой части — экран выбранной функции.
 */
@Composable
fun BotTreeScreen(
    botId: Long,
    localStore: LocalBotStore,
    repository: BotRepository,
    onOpen: (String) -> Unit, // маршрут
    onAddBot: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val states by LocalBotService.states.collectAsState()
    var profile by remember { mutableStateOf<BotProfile?>(null) }
    var expanded by remember { mutableStateOf(setOf("root")) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }

    LaunchedEffect(botId) {
        localStore.setActiveBot(botId)
        profile = localStore.profile(botId)
    }

    val st = states[botId]

    fun toggle(key: String) {
        expanded = if (key in expanded) expanded - key else expanded + key
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("← Боты") }
            TextButton(onClick = onAddBot) { Text("+ Бот") }
        }

        // ---------- корень: имя бота ----------
        Card(
            Modifier.fillMaxWidth().clickable { toggle("root") },
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.padding(14.dp).animateContentSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (st?.running == true) "🟢" else "⚪️",
                        style = MaterialTheme.typography.titleLarge)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(profile?.name.orEmpty().ifBlank { "Бот $botId" },
                            style = MaterialTheme.typography.titleLarge)
                        val uname = profile?.username.orEmpty()
                        if (uname.isNotBlank()) Text("@$uname",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text(if (expanded.contains("root")) "▾" else "▸")
                }
                if (expanded.contains("root")) {
                    Spacer(Modifier.height(8.dp))
                    st?.problems?.forEach { (_, text) ->
                        Text("⚠️ $text",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (st?.problems?.isNotEmpty() != true && st?.lastError?.isNotBlank() == true) {
                        Text("⚠️ ${st.lastError}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Сообщений: ${st?.processed ?: 0}" +
                        (if ((st?.lastPollAt ?: 0L) > 0)
                            " • опрос ${(System.currentTimeMillis() / 1000 - st!!.lastPollAt)} с назад"
                        else ""),
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { LocalBotService.startBot(context, botId) },
                            enabled = st?.running != true, modifier = Modifier.weight(1f)) {
                            Text("▶ Этот бот") }
                        OutlinedButton(onClick = { LocalBotService.stopBot(context, botId) },
                            enabled = st?.running == true, modifier = Modifier.weight(1f)) {
                            Text("■ Остановить") }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { LocalBotService.start(context) },
                            modifier = Modifier.weight(1f)) { Text("▶ Все боты") }
                        OutlinedButton(onClick = { LocalBotService.stop(context) },
                            modifier = Modifier.weight(1f)) { Text("■ Все боты") }
                        OutlinedButton(onClick = { confirmDelete = true },
                            modifier = Modifier.weight(1f)) { Text("🗑 Удалить") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Этот бот включён",
                                style = MaterialTheme.typography.bodyMedium)
                            Text("Выключенного бота сервис не запускает",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            profile?.enabled != false,
                            onCheckedChange = { on ->
                                scope.launch {
                                    val p = profile ?: return@launch
                                    localStore.updateProfile(p.copy(enabled = on))
                                    profile = p.copy(enabled = on)
                                }
                            },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // ---------- журнал — прямо в главном меню бота ----------
        Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .clickable { onOpen("devlog") }) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("📜", style = MaterialTheme.typography.titleMedium)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text("Журнал событий (лог)",
                        style = MaterialTheme.typography.titleSmall)
                    Text("загрузки, напоминания, ошибки — с копированием",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("→", style = MaterialTheme.typography.bodySmall)
            }
        }

        // ---------- общий лог приложения ----------
        Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .clickable { onOpen("applog") }) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🗃", style = MaterialTheme.typography.titleMedium)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text("Общий лог приложения",
                        style = MaterialTheme.typography.titleSmall)
                    Text("все события, экспорт в .txt",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("→", style = MaterialTheme.typography.bodySmall)
            }
        }

        // ---------- подпапки (набросок: скрипты/кнопки/ии/сценарии/тест/меню) ----------

        TreeBranch("📜", "Скрипты", "импорт и экспорт настроек кодом",
            expanded.contains("scripts"), { toggle("scripts") },
            listOf(
                TreeLeaf("Импорт настроек из кода", ".py / .js / .txt — по категориям") {
                    onOpen("import")
                },
                TreeLeaf("Экспорт настроек в код", "текущие настройки → Python-файл") {
                    onOpen("export")
                },
                TreeLeaf("Файлы бота и сервер", "media/scripts/docs + сервер по Wi-Fi") {
                    onOpen("files")
                },
                TreeLeaf("🧠 Промт для создания бота", "готовый промт для нейросети + «Копировать»") {
                    onOpen("prompt")
                },
            ))

        TreeBranch("🤖", "ИИ", "модель на телефоне, характер, чат",
            expanded.contains("ai"), { toggle("ai") },
            listOf(
                TreeLeaf("Чат с ИИ и раздумья", "потестировать модель вживую") {
                    onOpen("llmchat")
                },
                TreeLeaf("Модели ИИ", "скачать / выбрать / удалить") {
                    onOpen("llmdevice")
                },
                TreeLeaf("Настройки ИИ", "температура, память, паузы") {
                    onOpen("aisettings")
                },
            ))

        TreeBranch("📋", "Сценарии", "сценарий = правило + его кнопки; расписание, наборы",
            expanded.contains("scen"), { toggle("scen") },
            listOf(
                TreeLeaf("Правила ответов (сценарии)", "команда/фраза → ответ + свои кнопки") {
                    onOpen("localbot")
                },
                TreeLeaf("Кнопки бота", "клавиатура чата + обзор inline-кнопок сценариев") {
                    onOpen("buttons")
                },
                TreeLeaf("Напоминания (расписание)", "время, дни, кнопки") {
                    onOpen("reminders")
                },
                TreeLeaf("Наборы ответов", "шутки, сарказм, свои + редактор") {
                    onOpen("packs")
                },
                TreeLeaf("Поведение и паузы", "«печатает…», антифлуд, память ИИ") {
                    onOpen("behavior")
                },
            ))

        TreeBranch("🧪", "Тест", "имитация поведения без Telegram",
            expanded.contains("test"), { toggle("test") },
            listOf(
                TreeLeaf("Имитация поведения бота", "как ответит бот — прямо здесь") {
                    onOpen("simulate")
                },
            ))

        TreeBranch("☰", "Меню", "меню Telegram, приветствие, запасной ответ",
            expanded.contains("menu"), { toggle("menu") },
            listOf(
                TreeLeaf("Меню, приветствие, запасной ответ", "всё в «Настройках бота»") {
                    onOpen("botsettings")
                },
            ))

        Spacer(Modifier.height(10.dp))
        if (info.isNotBlank()) {
            Text(info, color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth()) {
            Text("⚠️ Сбросить настройки бота")
        }
        Text("Удаляет ВСЁ, что настроено у этого бота: правила и сценарии, расписание, "
            + "клавиатуру, характер и параметры ИИ, уточняющие вопросы, меню команд, канал "
            + "и объявления. Имя бота и токен остаются.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(24.dp))
    }

    if (confirmReset) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Сбросить настройки бота?") },
            text = {
                Text("Будут удалены: правила и сценарии, расписание напоминаний, клавиатура, "
                    + "характер и параметры ИИ, уточняющие вопросы, меню команд, канал и объявления. "
                    + "Действие необратимо. Имя бота и токен останутся.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    scope.launch {
                        LocalBotService.stopBot(context, botId)
                        repository.deleteBotRules(botId)
                        localStore.resetBot(botId)
                        info = "✅ Настройки бота сброшены до заводских"
                        com.botcontrol.admin.llm.DeviceLlm.log(
                            "♻️ Сброшены настройки бота $botId (правила, расписание, ИИ, меню)")
                    }
                }) { Text("Сбросить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Отмена") }
            },
        )
    }

    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить бота?") },
            text = { Text("«" + (profile?.name.orEmpty().ifBlank { "Бот $botId" }) +
                "» будет удалён с этого телефона вместе с токеном, правилами и настройками. Отменить нельзя.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    LocalBotService.stopBot(context, botId)
                    scope.launch {
                        localStore.removeProfile(botId)
                        onBack()
                    }
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun TreeBranch(
    icon: String,
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    items: List<TreeLeaf>,
) {
    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp).animateContentSize()) {
            Row(Modifier.fillMaxWidth().clickable { onToggle() },
                verticalAlignment = Alignment.CenterVertically) {
                Text(icon, style = MaterialTheme.typography.titleMedium)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (expanded) "▾" else "▸", style = MaterialTheme.typography.bodySmall)
            }
            if (expanded) {
                items.forEach { leaf ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    Row(Modifier.fillMaxWidth().clickable { leaf.onOpen() }
                        .padding(start = 30.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("•", style = MaterialTheme.typography.bodySmall)
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(leaf.title, style = MaterialTheme.typography.bodyMedium)
                            if (leaf.subtitle.isNotBlank()) {
                                Text(leaf.subtitle, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text("→", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private data class TreeLeaf(
    val title: String,
    val subtitle: String,
    val onOpen: () -> Unit,
)
