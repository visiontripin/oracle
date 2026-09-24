package com.botcontrol.admin.data

import com.botcontrol.admin.data.local.BotRuleEntity

class BotRepository {
    suspend fun botRules(botId: Long = -1L): List<BotRuleEntity> = emptyList()
}
