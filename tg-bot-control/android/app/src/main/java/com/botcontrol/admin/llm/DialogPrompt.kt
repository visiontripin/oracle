package com.botcontrol.admin.llm

/**
 * Собирает промт с историей диалога (контекст, как MAX_HISTORY в python-боте):
 * характер + предыдущие реплики + новое сообщение.
 */
object DialogPrompt {

    fun build(system: String, history: List<Pair<String, String>>, user: String): String {
        if (history.isEmpty()) {
            return if (system.isBlank()) user.trim() else "${system.trim()}\n\n${user.trim()}"
        }
        val sb = StringBuilder()
        if (system.isNotBlank()) sb.append(system.trim()).append("\n\n")
        sb.append("Предыдущий диалог:\n")
        history.forEach { (u, a) ->
            sb.append("Собеседник: ").append(u).append("\nТы: ").append(a).append("\n")
        }
        sb.append("\nНовое сообщение собеседника: ").append(user.trim())
        sb.append("\nТвой ответ:")
        return sb.toString()
    }
}

/**
 * Короткая память диалога по чатам (в памяти процесса — как user_history
 * в python-боте; после перезапуска сервиса контекст начинается заново).
 */
object ChatMemory {
    private val map = HashMap<String, MutableList<Pair<String, String>>>()

    private fun key(botId: Long, chatId: Long) = "$botId:$chatId"

    @Synchronized
    fun history(botId: Long, chatId: Long): List<Pair<String, String>> =
        map[key(botId, chatId)]?.toList().orEmpty()

    @Synchronized
    fun remember(botId: Long, chatId: Long, user: String, reply: String, limit: Int) {
        if (limit <= 0) return
        val list = map.getOrPut(key(botId, chatId)) { mutableListOf() }
        list.add(user to reply)
        while (list.size > limit) list.removeAt(0)
    }

    @Synchronized
    fun clear(botId: Long, chatId: Long) {
        map.remove(key(botId, chatId))
    }
}
