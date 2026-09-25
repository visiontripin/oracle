package com.botcontrol.admin.service

import com.botcontrol.admin.data.Anim
import com.botcontrol.admin.data.AnimSpec
import com.botcontrol.admin.data.InlineBtn
import com.botcontrol.admin.data.telegram.TelegramApi
import com.botcontrol.admin.llm.DeviceLlm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Проигрывает [AnimSpec] в Telegram: первый кадр — новым сообщением (или
 * правкой сообщения под кнопкой), дальше editMessageText каждые N мс,
 * в конце — итоговый текст и кнопки.
 *
 * • Работает в фоне (scope сервиса) — не блокирует ответы на другие сообщения.
 * • Одна анимация на чат: новая останавливает предыдущую.
 * • 429 Too Many Requests → ждём retry_after и повторяем кадр один раз;
 *   «message is not modified» — пропускаем; прочие ошибки — стоп + журнал.
 */
object AnimPlayer {

    private val jobs = ConcurrentHashMap<String, Job>()

    fun play(
        scope: CoroutineScope,
        api: TelegramApi,
        botId: Long,
        chatId: Long,
        spec: AnimSpec,
        user: String,
        packItems: List<String>,
        menu: List<InlineBtn>,
        editMessageId: Int? = null,
    ) {
        val key = "$botId:$chatId"
        jobs.remove(key)?.cancel()
        val job = scope.launch {
            try {
                run(api, botId, chatId, spec, user, packItems, menu, editMessageId)
            } finally {
                jobs.remove(key, coroutineContext[Job])
            }
        }
        jobs[key] = job
    }

    private suspend fun run(
        api: TelegramApi,
        botId: Long,
        chatId: Long,
        spec: AnimSpec,
        user: String,
        packItems: List<String>,
        menu: List<InlineBtn>,
        editMessageId: Int?,
    ) {
        val frames = Anim.frames(spec, user)
        val finalText = Anim.final(spec, user, packItems)
        val step = Anim.interval(spec)
        if (frames.isEmpty() && finalText.isBlank()) return
        val mode = if (spec.mono) "HTML" else null
        fun fmt(raw: String): String {
            val s = Anim.keepLayout(raw)
            return if (spec.mono) "<pre>${Anim.html(s)}</pre>" else s
        }

        DeviceLlm.log("🎞 [$botId] Анимация «${Anim.preset(spec.preset).title}»: кадров ${frames.size}, шаг ${step} мс")

        var mid = editMessageId
        var start = 0
        if (mid == null) {
            val first = frames.firstOrNull() ?: finalText
            mid = api.sendMessageForId(chatId, fmt(first), mode,
                if (frames.isEmpty()) menu else emptyList())
                .onFailure { DeviceLlm.log("❌ [$botId] Анимация: первый кадр не отправлен: ${it.message?.take(160)}") }
                .getOrNull()
            if (mid == null || mid <= 0) return
            start = 1
            if (frames.isEmpty()) return
        }

        for (i in start until frames.size) {
            if (i > 0 || editMessageId == null) delay(step)
            if (!edit(api, botId, chatId, mid, fmt(frames[i]), emptyList(), mode)) return
        }
        // Итог: обычный текст (не моноширинный) + кнопки меню.
        val last = if (finalText.isNotBlank()) finalText else null
        if (last != null || menu.isNotEmpty()) {
            delay(step)
            if (last != null) edit(api, botId, chatId, mid, last, menu, null)
            else edit(api, botId, chatId, mid, fmt(frames.last()), menu, mode)
        }
    }

    /** Правка кадра. false — остановить анимацию. */
    private suspend fun edit(
        api: TelegramApi, botId: Long, chatId: Long, mid: Int,
        text: String, menu: List<InlineBtn>, mode: String?,
    ): Boolean {
        repeat(2) { attempt ->
            val r = api.editMessageText(chatId, mid, text, menu, mode)
            if (r.isSuccess) return true
            val msg = r.exceptionOrNull()?.message.orEmpty()
            when {
                msg.contains("not modified", ignoreCase = true) -> return true
                msg.contains("HTTP 429") && attempt == 0 -> {
                    val wait = Regex("retry_after\\D{0,4}(\\d+)").find(msg)?.groupValues?.get(1)?.toLongOrNull() ?: 2L
                    DeviceLlm.log("⏳ [$botId] Анимация: Telegram просит паузу ${wait} с (слишком частые правки)")
                    delay((wait.coerceIn(1, 30)) * 1000L)
                }
                else -> {
                    DeviceLlm.log("❌ [$botId] Анимация остановлена: ${msg.take(160)}")
                    return false
                }
            }
        }
        return false
    }
}
