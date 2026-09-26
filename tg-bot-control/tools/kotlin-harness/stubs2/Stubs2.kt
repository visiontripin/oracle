package com.botcontrol.admin.data.local

data class BotRuleEntity(
    val id: Int = 0,
    val type: String = "",
    val pattern: String = "",
    val responseText: String = "",
    val enabled: Boolean = true,
    val actionType: String = "text",
    val script: String = "",
    val packId: String = "",
    val menu: String = "",
    val botId: Long = 1L,
)
