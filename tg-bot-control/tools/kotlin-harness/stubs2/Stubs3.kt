package com.botcontrol.admin.data

import com.botcontrol.admin.data.local.BotRuleEntity

class BotRepository {
    var rules: List<BotRuleEntity> = emptyList()
    suspend fun botRules(botId: Long = -1L): List<BotRuleEntity> = rules
}
