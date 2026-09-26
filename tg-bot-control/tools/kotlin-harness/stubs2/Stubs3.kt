package com.botcontrol.admin.data

import com.botcontrol.admin.data.local.BotRuleEntity

class BotRepository {
    var rules: List<BotRuleEntity> = emptyList()
    var rulesByBot: Map<Long, List<BotRuleEntity>> = emptyMap()
    suspend fun botRules(botId: Long = -1L): List<BotRuleEntity> = rulesByBot[botId] ?: rules
}
