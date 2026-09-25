package com.botcontrol.admin.service

import com.botcontrol.admin.llm.DeviceLlm

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

    /** «@username», пока имя не известно — «бот #id». */
    fun label(botId: Long): String =
        LocalBotService.states.value[botId]?.botUsername
            ?.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "бот #$botId"

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
