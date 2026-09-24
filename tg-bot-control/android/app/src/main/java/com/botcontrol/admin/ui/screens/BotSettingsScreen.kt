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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.botcontrol.admin.data.BotJson
import com.botcontrol.admin.data.LocalBotStore
import com.botcontrol.admin.data.MenuCommand
import com.botcontrol.admin.data.PerkurPresets
import com.botcontrol.admin.data.local.BotRuleEntity
import com.botcontrol.admin.data.telegram.TelegramApi
import com.botcontrol.admin.llm.DeviceLlm
import com.botcontrol.admin.ui.components.SectionTitle
import kotlinx.coroutines.launch

/**
 * Настройки бота: приветствие после /start, главное меню Telegram
 * (кнопка «Меню» с командами), подсказка по командам-правилам.
 */
@Composable
fun BotSettingsScreen(localStore: LocalBotStore, repository: com.botcontrol.admin.data.BotRepository,
                      onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var greeting by remember { mutableStateOf("") }
    var greetingLoaded by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<List<MenuCommand>>(emptyList()) }
    var commandRulesCount by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var channel by remember { mutableStateOf("") }
    var listingsOn by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        channel = localStore.channelId(localStore.activeBotId())
        listingsOn = localStore.listingsOn(localStore.activeBotId())
        menu = localStore.menuCommands()
        val rules = repository.botRules(localStore.activeBotId())
        commandRulesCount = rules.count { it.type == "command" }
        rules.firstOrNull {
            it.type == "command" && it.pattern.removePrefix("/").equals("start", true)
        }?.let { greeting = it.responseText }
        greetingLoaded = true
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text("Настройки бота", style = MaterialTheme.typography.titleLarge)
        }
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)

        // ---------- канал и режим объявлений ----------
        SectionTitle("Канал публикаций и объявления")
        Text("Куда бот публикует посты режима «Объявления» и события с флажком «в канал» (например, @daromvpl).",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            channel, { channel = it },
            label = { Text("Канал, например @daromvpl") },
            placeholder = { Text("@daromvpl") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = {
                scope.launch {
                    localStore.setChannelId(channel, localStore.activeBotId())
                    message = "✅ Канал сохранён: ${channel.trim()}"
                }
            }) { Text("💾 Сохранить канал") }
            Button(onClick = {
                scope.launch {
                    val nv = !listingsOn
                    localStore.setListingsOn(nv, localStore.activeBotId())
                    listingsOn = nv
                    message = if (nv)
                        "✅ Режим объявлений включён: «📝 Разместить объявление», «📋 Мои объявления», «❌ Отмена»"
                    else "⏹ Режим объявлений выключен"
                }
            }) { Text(if (listingsOn) "✅ Объявления: вкл" else "⏹ Объявления: выкл") }
        }
        Text("В режиме объявлений бот сам ведёт визард: описание → контакт → фото (до 3) → предпросмотр → публикация в канал с кнопкой «Написать автору». Совет: добавь в «Клавиатуру» кнопки «📝 Разместить объявление» и «📋 Мои объявления».",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))

        // ---------- приветствие /start ----------
        SectionTitle("Приветствие после /start")
        Text("Что бот ответит на первую команду /start. {user} заменится на имя пользователя.",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            greeting, { greeting = it },
            label = { Text("Текст приветствия") },
            placeholder = { Text("Симуляция активирована, {user}. 👋") },
            modifier = Modifier.fillMaxWidth().height(110.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = {
                scope.launch {
                    val botId = localStore.activeBotId()
                    val rules = repository.botRules(botId)
                    val existing = rules.firstOrNull {
                        it.type == "command" && it.pattern.removePrefix("/").equals("start", true)
                    }
                    if (existing != null) {
                        repository.saveBotRule(existing.copy(responseText = greeting))
                    } else {
                        repository.saveBotRule(
                            BotRuleEntity(
                                botId = localStore.activeBotId(),
                                type = "command", pattern = "/start",
                                responseText = greeting,
                                menu = BotJson.save(PerkurPresets.startMenu()),
                            ))
                    }
                    message = "Приветствие сохранено (правило /start)"
                }
            }, enabled = greeting.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Сохранить") }
            OutlinedButton(onClick = { greeting = "Симуляция активирована, {user}. 👋" }) {
                Text("Пример")
            }
        }

        // ---------- главное меню ----------
        SectionTitle("Главное меню Telegram (кнопка «Меню»)")
        Text("Список команд появится в Telegram слева от поля ввода. Команда — только латиница, без «/».",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        menu.forEachIndexed { index, cmd ->
            Card(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Column(Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Команда ${index + 1}", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = { menu = menu - cmd }) { Text("✕") }
                    }
                    OutlinedTextField(
                        cmd.command, { menu = menu.mapIndexed { i, m ->
                            if (i == index) m.copy(command = it) else m } },
                        label = { Text("Команда: латиница, цифры, _ (например start)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        cmd.description, { menu = menu.mapIndexed { i, m ->
                            if (i == index) m.copy(description = it) else m } },
                        label = { Text("Описание (видно в меню)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = {
                menu = menu + MenuCommand("", "")
            }, modifier = Modifier.weight(1f)) { Text("+ Команда меню") }
            OutlinedButton(onClick = {
                menu = listOf(
                    MenuCommand("start", "Запустить бота"),
                    MenuCommand("help", "Что умеет бот"),
                    MenuCommand("joke", "Случайная шутка"),
                )
            }, modifier = Modifier.weight(1f)) { Text("Пример") }
        }
        Spacer(Modifier.height(4.dp))
        Button(onClick = {
            scope.launch {
                localStore.setMenuCommands(menu.filter { it.command.isNotBlank() })
                val token = localStore.botToken()
                if (token.isNullOrBlank()) {
                    error = "Токен не задан — сначала добавь бота"
                    return@launch
                }
                TelegramApi(token)
                    .setMyCommands(menu.filter { it.command.isNotBlank() }
                        .map { it.command to it.description })
                    .onSuccess {
                        message = "Меню применено в Telegram — проверь кнопку «Меню» у бота"
                        DeviceLlm.log("📜 Главное меню применено вручную (${menu.size} команд)")
                    }
                    .onFailure { error = it.message ?: "Не удалось применить меню" }
            }
        }, enabled = menu.any { it.command.isNotBlank() }, modifier = Modifier.fillMaxWidth()) {
            Text("📣 Применить в Telegram")
        }
        Text("Также применяется автоматически при каждом запуске бота.",
            style = MaterialTheme.typography.bodySmall)

        // ---------- команды-правила ----------
        SectionTitle("Команды бота (что они выполняют)")
        Text("Команд — $commandRulesCount. Что делает каждая команда (/start, /help, /joke…), задаётся правилами на главном экране бота: «+ Добавить команду» → тип «Команда /…», действие — текст, набор, скрипт или ИИ.",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))
        Spacer(Modifier.height(18.dp))

        // ---------- сброс настроек бота ----------
        SectionTitle("Сброс настроек бота")
        Text("Удаляет ВСЁ, что настроено у этого бота: правила ответов, расписание, клавиатуру, характер и параметры ИИ, уточняющие вопросы, меню команд, напоминания, канал и объявления. Имя и токен бота остаются.",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth()) {
            Text("⚠️ Сбросить настройки бота до заводских")
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Сбросить настройки бота?") },
            text = { Text("Будут удалены: правила ответов, расписание напоминаний, клавиатура, характер и параметры ИИ, уточняющие вопросы, меню команд, канал и объявления. Действие необратимо. Имя бота и токен останутся.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    scope.launch {
                        val botId = localStore.activeBotId()
                        repository.deleteBotRules(botId)
                        localStore.resetBot(botId)
                        message = "✅ Настройки бота сброшены до заводских. Перезайди в экран."
                        error = ""
                    }
                }) { Text("Сбросить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Отмена") }
            },
        )
    }
}
