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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.botcontrol.admin.data.BotRepository
import com.botcontrol.admin.data.LocalBotStore
import com.botcontrol.admin.data.PerkurPresets
import com.botcontrol.admin.data.ScriptImporter
import com.botcontrol.admin.data.withIds
import com.botcontrol.admin.llm.DeviceLlm
import kotlinx.coroutines.launch

/**
 * Импорт настроек готового бота из исходника (.py / .js / .txt):
 * вставь текст кода или выбери файл — параметры сами разложатся по
 * категориям настроек бота. Есть и встроенный пример (перкур-бот).
 */
@Composable
fun ImportScreen(
    localStore: LocalBotStore,
    repository: BotRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf("") }
    var parsed by remember { mutableStateOf<ScriptImporter.Parsed?>(null) }
    val checked = remember { mutableStateMapOf<String, Boolean>() }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader().readText()
                }.orEmpty()
            }.onSuccess { text ->
                source = text
                parsed = ScriptImporter.parse(text)
                message = "Файл прочитан: ${text.length} символов"
            }.onFailure { error = "Не удалось прочитать файл: ${it.message}" }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text("Импорт настроек", style = MaterialTheme.typography.titleLarge)
        }
        Text("Вставь код готового бота (Python: telebot/aiogram, JavaScript) или текст с параметрами — приложение найдёт характер ИИ, команды, расписание, наборы ответов, кнопки и разложит по настройкам.",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            source, { source = it },
            label = { Text("Код бота (Python / JavaScript / TXT)") },
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().height(150.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = {
                if (source.isBlank()) { error = "Сначала вставь текст кода"; return@Button }
                val p = ScriptImporter.parse(source)
                if (p.isEmpty()) {
                    error = "Знакомых параметров не нашлось. Проверь, что в коде есть SYSTEM_PROMPT, команды, списки фраз или расписание."
                    parsed = null
                } else {
                    error = ""
                    parsed = p
                }
            }, modifier = Modifier.weight(1f)) { Text("🔍 Разобрать") }
            OutlinedButton(onClick = { filePicker.launch("*/*") }, modifier = Modifier.weight(1f)) {
                Text("📂 Выбрать файл")
            }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))

        // ---------- найденное по категориям ----------
        var modelHint by remember { mutableStateOf("") }
        LaunchedEffect(parsed) {
            val p = parsed ?: return@LaunchedEffect
            if (p.systemPrompt != null && localStore.aiModel(localStore.activeBotId()).isBlank()) {
                modelHint = "⚠️ В коде бот отвечал через ИИ (LLM). Чтобы это работало как в оригинале, открой «ИИ → Модели ИИ» и скачай/выбери модель."
            } else modelHint = ""
        }
        if (modelHint.isNotBlank()) {
            Text(modelHint, color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodySmall)
        }
        parsed?.let { p ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Найдено — отметь, что применить:", style = MaterialTheme.typography.titleSmall)
                    val items = p.summary()
                    items.forEach { line ->
                        val key = line
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = checked[key] ?: true,
                                onCheckedChange = { checked[key] = it },
                            )
                            Text(line, style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Button(onClick = {
                        scope.launch {
                            var botId = localStore.activeBotId()
                            if (botId <= 0L) botId =
                                localStore.profiles().firstOrNull()?.id ?: 1L
                            // Список категорий может не содержать строку (нет такой
                            // секции в коде) — отсутствие = применять.
                            fun wanted(prefix: String): Boolean =
                                items.firstOrNull { it.startsWith(prefix) }
                                    ?.let { checked[it] } ?: true

                            if (p.systemPrompt != null && wanted("Характер"))
                                localStore.setSystemPrompt(p.systemPrompt, botId)
                            p.temperature?.let { if (wanted("Температура"))
                                localStore.setAiTemperature(it, botId) }
                            p.maxTokens?.let { if (wanted("Максимум токенов"))
                                localStore.setAiMaxTokens(it, botId) }
                            p.topK?.let { if (wanted("Top-K"))
                                localStore.setAiTopK(it, botId) }
                            p.historyLimit?.let { if (wanted("Память"))
                                localStore.setHistoryLimit(it, botId) }
                            p.cooldownSec?.let { if (wanted("Пауза"))
                                localStore.setCooldownSec(it, botId) }
                            p.typingSec?.let { if (wanted("«Печатает"))
                                localStore.setTypingSeconds(it, botId) }
                            if (p.clarify.isNotEmpty() && wanted("Уточняющих"))
                                localStore.setClarifyQuestions(p.clarify, botId)
                            if (p.commands.isNotEmpty() && wanted("Команды")) {
                                val existing = localStore.menuCommands(botId)
                                    .filterNot { mc -> p.commands.any { it == mc.command } }
                                localStore.setMenuCommands(
                                    existing + p.commands.map {
                                        com.botcontrol.admin.data.MenuCommand(it, "Команда из импорта")
                                    }, botId)
                            }
                            if (p.greeting != null && wanted("Приветствие")) {
                                val rules = repository.botRules(botId)
                                val startRule = rules.firstOrNull {
                                    it.type == "command" && it.pattern.removePrefix("/").equals("start", true)
                                }
                                // В оригинале /start присылает сообщение с кнопками «Запуск/Стоп».
                                val startMenuJson = if (p.startMenu)
                                    com.botcontrol.admin.data.BotJson.save(
                                        PerkurPresets.startMenu().withIds())
                                else startRule?.menu.orEmpty()
                                val normalizedGreeting = p.greeting
                                    .replace("{name}", "{user}")
                                    .replace("{username}", "{user}")
                                if (startRule != null) {
                                    repository.saveBotRule(startRule.copy(
                                        responseText = normalizedGreeting, menu = startMenuJson))
                                } else {
                                    repository.saveBotRule(
                                        com.botcontrol.admin.data.local.BotRuleEntity(
                                            type = "command", pattern = "/start",
                                            responseText = normalizedGreeting,
                                            menu = startMenuJson,
                                            botId = botId,
                                        ))
                                }
                            }
                            // Тексты остальных команд кода (/help и свои) → правила-команды.
                            if (wanted("Команды")) {
                                ScriptImporter.extractCommandTexts(source).forEach { (cmd, cmdText) ->
                                    val rules0 = repository.botRules(botId)
                                    val rule = rules0.firstOrNull {
                                        it.type == "command" &&
                                            it.pattern.removePrefix("/").equals(cmd, true)
                                    }
                                    if (rule != null) {
                                        repository.saveBotRule(rule.copy(responseText = cmdText))
                                    } else {
                                        repository.saveBotRule(
                                            com.botcontrol.admin.data.local.BotRuleEntity(
                                                type = "command", pattern = "/$cmd",
                                                responseText = cmdText, botId = botId,
                                            ))
                                    }
                                }
                            }
                            if (p.packs.isNotEmpty() && wanted("Набор")) {
                                // Мерж по имени: наборы с тем же названием (включая
                                // пресетные «😂 Шутки» и сарказм) обновляют содержимое,
                                // сохраняя свой id — кнопки меню продолжают работать.
                                val current = localStore.packs()
                                val merged = p.packs.map { imported ->
                                    val existing = current.firstOrNull { it.name == imported.name }
                                    if (existing != null) {
                                        existing.copy(items = imported.items)
                                    } else imported
                                }
                                val keep = current.filterNot { pack ->
                                    merged.any { it.id == pack.id }
                                }
                                localStore.setPacks(keep + merged)
                            }
                            if (p.schedule.isNotEmpty() && wanted("Событий")) {
                                val keep = localStore.schedule(botId)
                                    .filterNot { e -> p.schedule.any { it.hour == e.hour && it.minute == e.minute } }
                                localStore.setSchedule(keep + p.schedule, botId)
                                // В оригинале расписание включено сразу после запуска —
                                // иначе «напоминания не приходят».
                                localStore.setRemindersOn(true, botId)
                            }
                            // Inline-кнопки кода («Запуск/Стоп», «Покурил»…) — это меню
                            // под сообщениями, они уже применены к /start и событиям.
                            // Клавиатуру чата из кода не трогаем: в python-боте её не было.
                            DeviceLlm.log("📥 Импорт применён: ${p.summary().size} категорий")
                            message = "✅ Применено к боту. Проверь соответствующие вкладки."
                            parsed = null
                            source = ""
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("✅ Применить к боту") }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = {
            scope.launch {
                applyPerkurPreset(
                    context, repository, localStore)
                message = "✅ Применён встроенный пример: перкур-бот"
            }
        }, modifier = Modifier.fillMaxWidth()) {
            Text("⚡ Встроенный пример: перкур-бот (Агент Смит)")
        }
        Spacer(Modifier.height(24.dp))
    }
}
