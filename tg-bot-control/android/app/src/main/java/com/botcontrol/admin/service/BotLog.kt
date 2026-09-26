package com.botcontrol.admin.service

import com.botcontrol.admin.data.LocalBotStore
import com.botcontrol.admin.llm.DeviceLlm
import java.util.concurrent.ConcurrentHashMap

/**
 * Журнал событий с меткой бота. Боты работают параллельно, и без метки
 * строки «📩 Сообщение / Проверяю правила / Источник» разных ботов
 * перемешиваются — не понять, какой бот что ответил.
 *
 * Формат: «📩 [@name] Сообщение: …», «   • [@name] Проверяю правила (3)».
 * Метку вставляем после ведущего эмодзи/маркера, чтобы иконки
 * оставались в начале строки, как раньше.
 */
object BotLog {

    /** Имена из профилей — для строк экранов, когда бот не запущен. */
    private val names = ConcurrentHashMap<Long, String>()

    /** «@username», пока имя не известно — «бот #id». */
    fun label(botId: Long): String =
        (LocalBotService.states.value[botId]?.botUsername?.takeIf { it.isNotBlank() }
            ?: names[botId])?.let { "@$it" } ?: "бот #$botId"

    /** Для экранов: сначала подтянуть имя из профиля, затем записать строку. */
    suspend fun log(store: LocalBotStore, botId: Long, line: String) {
        store.profile(botId)?.username?.takeIf { it.isNotBlank() }?.let { names[botId] = it }
        log(botId, line)
    }

    fun tag(botId: Long): String = "[${label(botId)}]"

    fun log(botId: Long, line: String) = DeviceLlm.log(tagged(tag(botId), line))

    /** Чистая функция — вынесена для наглядности и проверки. */
    fun tagged(tag: String, line: String): String {
        if (line.contains(tag)) return line
        val body = line.trimStart()
        val indent = line.substring(0, line.length - body.length)
        val first = body.firstOrNull() ?: return tag
        if (first.isLetterOrDigit() || first == '\'' || first == '«' || first == '"') {
            return "$indent$tag $body"
        }
        // Ведущий маркер (эмодзи, «•», «⚠️») — метка после него.
        val sp = body.indexOf(' ')
        return if (sp < 0) "$indent$body $tag"
        else indent + body.substring(0, sp + 1) + tag + " " + body.substring(sp + 1)
    }
}
