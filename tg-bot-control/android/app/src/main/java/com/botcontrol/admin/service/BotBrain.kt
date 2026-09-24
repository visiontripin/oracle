package com.botcontrol.admin.service

import android.content.Context
import com.botcontrol.admin.BotControlApp
import com.botcontrol.admin.data.BotJson
import com.botcontrol.admin.data.InlineBtn
import com.botcontrol.admin.data.LocalBotStore
import com.botcontrol.admin.data.ReplyPack
import com.botcontrol.admin.data.withIds
import com.botcontrol.admin.llm.BotScript
import com.botcontrol.admin.llm.ChatMemory
import com.botcontrol.admin.llm.DeviceLlm
import com.botcontrol.admin.llm.DialogPrompt

/** Результат «размышлений» бота над сообщением. */
data class BotDecision(
    val source: String,   // правило / скрипт / набор / ИИ / уточняющий вопрос / запасной ответ / кулдаун
    val reply: String,    // текст ответа (пусто = молчать)
    val menu: List<InlineBtn> = emptyList(),
)

/**
 * Единый «мозг» бота. Порядок:
 *  1) правила (текст / скрипт JS / набор — всегда КОД, без ИИ);
 *  2) сценарий ИИ — только явно: команда /chat (+ вопрос) или правило с
 *     действием «ИИ»; ИИ по умолчанию выключен и никогда не отвечает
 *     «на всё» — функции бота выполняются исполнением кода;
 *  3) антифлуд;
 *  4) кодовый запасной ответ (уточняющий вопрос / default).
 * Используется и сервисом (реальный Telegram), и вкладкой имитации —
 * поведение в тесте и в бою гарантированно одинаковое.
 */
object BotBrain {

    private val lastAnswerAt = HashMap<Long, Long>() // chat -> epoch sec

    /** Проблема ИИ висит в статусе бота до первого успешного ответа. */
    private fun addLlmProblem(botId: Long, text: String) {
        val cur = LocalBotService.states.value[botId] ?: return
        if (cur.problems["llm"] != text) {
            LocalBotService.states.value = LocalBotService.states.value +
                (botId to cur.copy(problems = cur.problems + ("llm" to text)))
        }
    }

    private fun resolveLlmProblem(botId: Long) {
        val cur = LocalBotService.states.value[botId] ?: return
        if ("llm" in cur.problems) {
            LocalBotService.states.value = LocalBotService.states.value +
                (botId to cur.copy(problems = cur.problems - "llm"))
        }
    }

    /** Параметры генерации из настроек конкретного бота. */
    private suspend fun engineParams(store: LocalBotStore, botId: Long): com.botcontrol.admin.llm.EngineParams =
        com.botcontrol.admin.llm.EngineParams(
            temperature = store.aiTemperature(botId),
            topK = store.aiTopK(botId),
            maxTokens = store.aiMaxTokens(botId),
        )

    fun parseMenu(json: String): List<InlineBtn> =
        runCatching { BotJson.menu(json) }.getOrDefault(emptyList()).withIds()

    fun randomFrom(packs: List<ReplyPack>, packId: String): String =
        packs.firstOrNull { it.id == packId }?.items?.randomOrNull()
            ?: packs.firstOrNull { it.name == packId }?.items?.randomOrNull()
            ?: packs.firstOrNull { it.id.contains(packId) || packId.contains(it.id, ignoreCase = true) }
                ?.items?.randomOrNull()
            ?: packs.firstOrNull { pack ->
                pack.items.isNotEmpty() && packId.isNotBlank() &&
                    (pack.name.contains(packId, ignoreCase = true))
            }?.items?.randomOrNull()
            ?: "Набор пуст — добавь ответы в «🎲 Наборы ответов»"

    suspend fun decide(
        context: Context,
        store: LocalBotStore,
        botId: Long,
        chatId: Long,
        firstName: String,
        text: String,
        respectCooldown: Boolean = true,
        onLlmStart: (suspend () -> Unit)? = null,
        trace: ((String) -> Unit)? = null,
    ): BotDecision {
        val app = context.applicationContext as BotControlApp
        val botName = LocalBotService.states.value[botId]?.botUsername.orEmpty()

        // 1) декларативные правила
        val rules = app.repository.botRules(botId).filter { it.enabled }
        trace?.invoke("Проверяю правила (${rules.size})…")
        val packs = store.packs()
        for (rule in rules) {
            val matched = when (rule.type) {
                "command" -> {
                    val first = text.split(Regex("\\s+")).firstOrNull().orEmpty()
                    first.substringBefore("@").equals(
                        if (rule.pattern.startsWith("/")) rule.pattern else "/${rule.pattern}",
                        ignoreCase = true,
                    )
                }
                "button" -> text.equals(rule.pattern, ignoreCase = true)
                else -> text.contains(rule.pattern, ignoreCase = true) && rule.pattern.isNotBlank()
            }
            if (!matched) continue
            trace?.invoke("✓ Сработало правило «${rule.pattern}» (тип: ${rule.type})")
            return when (rule.actionType) {
                "llm" -> {
                    trace?.invoke("Действие правила: отвечает ИИ")
                    llmReply(context, store, botId, chatId, firstName, text, onLlmStart, trace)
                }
                "script" -> {
                    trace?.invoke("Действие правила: скрипт JS")
                    BotScript.run(rule.script, firstName, text, chatId).fold(
                        onSuccess = {
                            trace?.invoke("Скрипт выполнен")
                            BotDecision("скрипт «${rule.pattern}»", it.ifBlank { "…" },
                                parseMenu(rule.menu))
                        },
                        onFailure = {
                            trace?.invoke("Ошибка скрипта: ${it.message?.take(100)}")
                            BotDecision("скрипт «${rule.pattern}» (ошибка)",
                                "Ошибка скрипта: ${it.message?.take(140)}", parseMenu(rule.menu))
                        },
                    )
                }
                "pack" -> {
                    val reply = randomFrom(packs, rule.packId)
                    trace?.invoke("Действие правила: случайное из набора")
                    BotDecision("набор «${packs.firstOrNull { it.id == rule.packId }?.name ?: rule.packId}»",
                        reply, parseMenu(rule.menu))
                }
                else -> BotDecision("правило «${rule.pattern}»",
                    rule.responseText
                        .replace("{user}", firstName)
                        .replace("{text}", text)
                        .replace("{bot}", if (botName.isNotBlank()) "@$botName" else ""),
                    parseMenu(rule.menu))
            }
        }
        trace?.invoke("Ни одно правило не подошло")

        // 2) Явная команда «ИИ-сценарий»: /chat (или /ai) + вопрос.
    //    ИИ — это ФИЧА, отдельный сценарий, а не ответ «на всё».
        val aiCommand = Regex("^/(chat|ai)(@\\w+)?\\s*(.*)$", RegexOption.IGNORE_CASE).find(text)
        if (aiCommand != null) {
            val question = aiCommand.groupValues[3].trim()
            return if (question.isBlank()) {
                BotDecision("ИИ-сценарий",
                    "Спроси так: /chat + вопрос. ИИ отвечает только в этом сценарии " +
                        "и по правилам с действием «ИИ» — всё остальное бот делает кодом.")
            } else if (!store.llmEnabled(botId)) {
                BotDecision("ИИ-сценарий",
                    "🤖 ИИ выключен: включи в «Сценарии → Правила ответов» (переключатель «ИИ»). " +
                        "Остальные функции работают без ИИ.")
            } else if (store.aiModel(botId).isBlank()) {
                BotDecision("ИИ-сценарий",
                    "🤖 ИИ не настроен: «ИИ → Модели ИИ» → скачай модель и выбери её.")
            } else {
                trace?.invoke("🤖 Сценарий /chat — спрашиваю ИИ…")
                llmReply(context, store, botId, chatId, firstName, question, onLlmStart, trace)
            }
        }

        // 3) Антифлуд на «незнакомых» сообщениях (как ANSWER_COOLDOWN в оригинале)
        val cd = store.cooldownSec().toLong()
        if (respectCooldown && cd > 0) {
            val now = System.currentTimeMillis() / 1000
            val last = lastAnswerAt[chatId] ?: 0L
            if (now - last < cd) {
                trace?.invoke("⏳ Кулдаун ${cd} с — молчу")
                return BotDecision("кулдаун", "")
            }
            lastAnswerAt[chatId] = now
        }

        // 4) Кодовый запасной ответ (уточняющий вопрос или default).
        //    ИИ здесь НЕ вызывается: он — только в сценариях (шаг 2, правило «ИИ»).
        return fallback(store)
    }

    private suspend fun llmReply(
        context: Context,
        store: LocalBotStore,
        botId: Long,
        chatId: Long,
        firstName: String,
        text: String,
        onLlmStart: (suspend () -> Unit)?,
        trace: ((String) -> Unit)?,
    ): BotDecision {
        val model = store.aiModel(botId)
        if (!store.llmEnabled(botId) || model.isBlank()) {
            trace?.invoke("ИИ выключен или модель не выбрана («Модели ИИ» → выбрать)")
            return fallback(store)
        }
        trace?.invoke("🤖 Спрашиваю ИИ (модель: $model)…")
        onLlmStart?.invoke()
        val history = ChatMemory.history(botId, chatId)
        if (history.isNotEmpty()) trace?.invoke("Контекст: ${history.size} прошлых реплик")
        val prompt = DialogPrompt.build(store.systemPrompt(botId), history, text)
        return DeviceLlm.ensureLoaded(context, model, engineParams(store, botId))
            .mapCatching { DeviceLlm.generate("", prompt).getOrThrow() }
            .fold(
                onSuccess = { reply ->
                    ChatMemory.remember(botId, chatId, text, reply, store.historyLimit(botId))
                    trace?.invoke("ИИ ответил (${reply.length} симв.)")
                    resolveLlmProblem(botId)
                    BotDecision("ИИ", reply)
                },
                onFailure = { e ->
                    trace?.invoke("❌ Ошибка ИИ: ${e.message?.take(120)}")
                    addLlmProblem(botId, "ИИ: ${e.message?.take(120)}")
                    fallback(store)
                },
            )
    }

    private suspend fun fallback(store: LocalBotStore): BotDecision {
        val clarify = if (store.clarifyEnabled()) {
            store.clarifyQuestions().randomOrNull().orEmpty()
        } else ""
        val reply = clarify.ifBlank { store.defaultReply() }
        return BotDecision(
            if (clarify.isNotBlank()) "уточняющий вопрос" else "запасной ответ",
            reply,
        )
    }
}
