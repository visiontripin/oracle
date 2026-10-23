package com.botcontrol.admin.data

/** Заглушка хранилища с состоянием (для теста экспорт ↔ импорт). */
class LocalBotStore {
    var llmOn = false
    var prompt = ""
    var packList: List<ReplyPack> = emptyList()
    var clarifyList: List<String> = emptyList()
    var keyboardList: List<String> = emptyList()
    var events: List<ScheduleEvent> = emptyList()
    var cooldown = 10
    /** Мультибот (тест изоляции): профили и расписание по botId. */
    var profileList: List<BotProfile> = emptyList()
    var eventsByBot: Map<Long, List<ScheduleEvent>> = emptyMap()
    suspend fun profiles(): List<BotProfile> = profileList
    var typing = 4

    suspend fun llmEnabled(botId: Long = -1L): Boolean = llmOn
    suspend fun systemPrompt(botId: Long = -1L): String = prompt
    suspend fun aiTemperature(botId: Long = -1L): Float = 0.7f
    suspend fun aiTopK(botId: Long = -1L): Int = 40
    suspend fun aiMaxTokens(botId: Long = -1L): Int = 80
    suspend fun historyLimit(botId: Long = -1L): Int = 4
    suspend fun cooldownSec(botId: Long = -1L): Int = cooldown
    suspend fun typingSeconds(botId: Long = -1L): Int = typing
    suspend fun packs(): List<ReplyPack> = packList
    suspend fun clarifyQuestions(botId: Long = -1L): List<String> = clarifyList
    suspend fun keyboard(botId: Long = -1L): List<String> = keyboardList
    suspend fun schedule(botId: Long = -1L): List<ScheduleEvent> = eventsByBot[botId] ?: events
}

/** Как в LocalBotStore.kt (сам файл Android-зависим). */
data class BotProfile(
    val id: Long = 0L,
    val name: String = "",
    val username: String = "",
    val enabled: Boolean = true,
)
