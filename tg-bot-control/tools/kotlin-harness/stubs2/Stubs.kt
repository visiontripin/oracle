package com.botcontrol.admin.data

class LocalBotStore {
    suspend fun systemPrompt(botId: Long = -1L): String = ""
    suspend fun aiTemperature(botId: Long = -1L): Float = 0.7f
    suspend fun aiTopK(botId: Long = -1L): Int = 40
    suspend fun aiMaxTokens(botId: Long = -1L): Int = 80
    suspend fun historyLimit(botId: Long = -1L): Int = 4
    suspend fun cooldownSec(botId: Long = -1L): Int = 10
    suspend fun typingSeconds(botId: Long = -1L): Int = 4
    suspend fun packs(): List<ReplyPack> = emptyList()
    suspend fun clarifyQuestions(botId: Long = -1L): List<String> = emptyList()
    suspend fun keyboard(botId: Long = -1L): List<String> = emptyList()
    suspend fun schedule(botId: Long = -1L): List<ScheduleEvent> = emptyList()
}
