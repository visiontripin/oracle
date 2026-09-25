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
 * Фото-кадры («photo: …», см. [Anim.photoFrames]): первый — sendPhoto,
 * дальше editMessageMedia (новая картинка) или editMessageCaption (та же
 * картинка, новая подпись). Загруженные с телефона картинки запоминаются
 * по file_id — повторный показ не грузит файл заново.
 *
 * • Работает в фоне (scope сервиса) — не блокирует ответы на другие сообщения.
 * • Одна анимация на чат: новая останавливает предыдущую.
 * • 429 Too Many Requests → ждём retry_after и повторяем кадр один раз;
 *   «message is not modified» — пропускаем; прочие ошибки — стоп + журнал.
 */
object AnimPlayer {

    private val jobs = ConcurrentHashMap<String, Job>()
    /** «botId|источник» → file_id Telegram (картинка уже загружена). */
    private val fileIds = ConcurrentHashMap<String, String>()

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
        val finalText = Anim.final(spec, user, packItems)
        if (Anim.isPhotoAnim(spec)) {
            runPhoto(api, botId, chatId, spec, user, finalText, menu, editMessageId)
            return
        }
        val frames = Anim.frames(spec, user)
        val step = Anim.interval(spec)
        if (frames.isEmpty() && finalText.isBlank()) return
        val mode = if (spec.mono) "HTML" else null
        fun fmt(raw: String): String {
            val s = Anim.keepLayout(raw)
            return if (spec.mono) "<pre>${Anim.html(s)}</pre>" else s
        }

        DeviceLlm.log("🎞 [$botId] Анимация «${Anim.preset(spec.preset).title}»: кадров ${frames.size}, шаг ${step} мс")

        suspend fun sendNew(text: String, withMenu: Boolean): Int? =
            api.sendMessageForId(chatId, fmt(text), mode, if (withMenu) menu else emptyList())
                .onFailure { DeviceLlm.log("❌ [$botId] Анимация: первый кадр не отправлен: ${it.message?.take(160)}") }
                .getOrNull()?.takeIf { it > 0 }

        var mid: Int
        var start = 0
        if (editMessageId == null) {
            mid = sendNew(frames.firstOrNull() ?: finalText, frames.isEmpty()) ?: return
            start = 1
            if (frames.isEmpty()) return
        } else {
            mid = editMessageId
        }

        for (i in start until frames.size) {
            if (i > 0 || editMessageId == null) delay(step)
            val err = attempt(botId) { api.editMessageText(chatId, mid, fmt(frames[i]), emptyList(), mode) }
            if (err != null) {
                // Под кнопкой фото (в нём нет текста) — анимируем новым сообщением.
                if (i == 0 && editMessageId != null) {
                    DeviceLlm.log("ℹ️ [$botId] Анимация: сообщение под кнопкой не текстовое — показываю новым сообщением")
                    mid = sendNew(frames[0], false) ?: return
                    continue
                }
                DeviceLlm.log("❌ [$botId] Анимация остановлена: ${err.take(160)}")
                return
            }
        }
        // Итог: обычный текст (не моноширинный) + кнопки меню.
        val last = if (finalText.isNotBlank()) finalText else null
        if (last != null || menu.isNotEmpty()) {
            delay(step)
            val err = if (last != null) attempt(botId) { api.editMessageText(chatId, mid, last, menu, null) }
            else attempt(botId) { api.editMessageText(chatId, mid, fmt(frames.last()), menu, mode) }
            err?.let { DeviceLlm.log("❌ [$botId] Анимация: итог не показан: ${it.take(160)}") }
        }
    }

    /** Фото-анимация: sendPhoto → editMessageMedia / editMessageCaption. */
    private suspend fun runPhoto(
        api: TelegramApi,
        botId: Long,
        chatId: Long,
        spec: AnimSpec,
        user: String,
        finalText: String,
        menu: List<InlineBtn>,
        editMessageId: Int?,
    ) {
        val frames = Anim.photoFrames(spec, user)
        if (frames.isEmpty()) return
        val step = Anim.interval(spec)
        DeviceLlm.log("🖼 [$botId] Фото-анимация: кадров ${frames.size}, шаг ${step} мс")

        fun resolve(src: String) = fileIds["$botId|$src"] ?: src
        fun remember(src: String, fileId: String) {
            if (fileId.isNotBlank() && Anim.isLocalSrc(src)) fileIds["$botId|$src"] = fileId
        }
        val onlyOne = frames.size == 1 && finalText.isBlank()

        suspend fun sendFirst(f: Anim.PhotoFrame): Int? {
            val r = api.sendPhotoForId(chatId, resolve(f.src), f.caption, if (onlyOne) menu else emptyList())
            r.onFailure { DeviceLlm.log("❌ [$botId] Фото-анимация: первый кадр не отправлен: ${it.message?.take(160)}") }
            val (id, fileId) = r.getOrNull() ?: return null
            remember(f.src, fileId)
            return id.takeIf { it > 0 }
        }

        var mid: Int? = editMessageId
        var curSrc: String? = null
        for ((i, f) in frames.withIndex()) {
            val m = mid
            if (m == null) {
                mid = sendFirst(f) ?: return
                curSrc = f.src
                continue
            }
            if (i > 0 || editMessageId == null) delay(step)
            val err = if (f.src == curSrc) {
                attempt(botId) { api.editMessageCaption(chatId, m, f.caption) }
            } else {
                attempt(botId) {
                    api.editMessageMedia(chatId, m, resolve(f.src), f.caption).onSuccess { remember(f.src, it) }
                }
            }
            if (err != null) {
                if (i == 0 && editMessageId != null) {
                    // Под кнопкой текстовое сообщение — картинку в него не вставить.
                    DeviceLlm.log("ℹ️ [$botId] Фото-анимация: сообщение под кнопкой текстовое — показываю новым сообщением")
                    mid = sendFirst(f) ?: return
                    curSrc = f.src
                    continue
                }
                DeviceLlm.log("❌ [$botId] Фото-анимация остановлена: ${err.take(160)}")
                return
            }
            curSrc = f.src
        }
        val m = mid ?: return
        if (!onlyOne && (finalText.isNotBlank() || menu.isNotEmpty())) {
            delay(step)
            attempt(botId) { api.editMessageCaption(chatId, m, finalText.ifBlank { frames.last().caption }, menu) }
                ?.let { DeviceLlm.log("❌ [$botId] Фото-анимация: итог не показан: ${it.take(160)}") }
        }
    }

    /**
     * Один вызов Bot API с повтором при 429 (ждём retry_after).
     * null — успех («message is not modified» тоже успех), иначе текст ошибки.
     */
    private suspend fun attempt(botId: Long, block: suspend () -> Result<*>): String? {
        repeat(2) { n ->
            val r = block()
            if (r.isSuccess) return null
            val msg = r.exceptionOrNull()?.message.orEmpty()
            when {
                msg.contains("not modified", ignoreCase = true) -> return null
                msg.contains("HTTP 429") && n == 0 -> {
                    val wait = Regex("retry_after\\D{0,4}(\\d+)").find(msg)?.groupValues?.get(1)?.toLongOrNull() ?: 2L
                    DeviceLlm.log("⏳ [$botId] Анимация: Telegram просит паузу ${wait} с (слишком частые правки)")
                    delay(wait.coerceIn(1, 30) * 1000L)
                }
                else -> return msg.ifBlank { "ошибка" }
            }
        }
        return "HTTP 429: Telegram просит паузу"
    }
}
