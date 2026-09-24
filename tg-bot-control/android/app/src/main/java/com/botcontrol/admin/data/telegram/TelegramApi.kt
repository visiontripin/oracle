package com.botcontrol.admin.data.telegram

import com.botcontrol.admin.data.InlineBtn
import com.botcontrol.admin.data.layoutRows
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** One incoming Telegram message (subset we care about). */
data class TgMessage(
    val updateId: Long,
    val chatId: Long,
    val text: String,
    val firstName: String,
    /** Пусто для текста; иначе человекочитаемый тип: «фото», «стикер», «голосовое»… */
    val kind: String = "",
    /** file_id наибольшего фото (kind == "фото"). */
    val photoId: String = "",
)

/** Нажатие inline-кнопки (callback_query). */
data class TgCallback(
    val updateId: Long,
    val callbackId: String,
    val chatId: Long,
    val messageId: Int,
    val data: String,
    val firstName: String,
)

/**
 * Minimal Telegram Bot API client (long polling) for the on-phone bot mode.
 * Endpoints: getMe, getUpdates, sendMessage, sendChatAction,
 * answerCallbackQuery, editMessageText, deleteWebhook — plain HTTPS + JSON.
 */
class TelegramApi(private val token: String) {

    private val json = "application/json; charset=utf-8".toMediaType()

    // Long polling holds the connection open -> generous read timeout.
    private val pollClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val fastClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun url(method: String) = "https://api.telegram.org/bot$token/$method"

    private inline fun <T> parse(body: String, block: (root: com.google.gson.JsonObject) -> T): T {
        val root = JsonParser.parseString(body).asJsonObject
        if (!root.get("ok").asBoolean) {
            val desc = root.get("description")?.asString ?: "unknown error"
            error("Telegram API: $desc")
        }
        return block(root)
    }

    /**
     * Returns @username of the bot. MUST be suspend+IO: performs a blocking
     * network call — calling it from the main thread crashes with
     * NetworkOnMainThreadException and the check fails for EVERY token.
     */
    suspend fun validateToken(): Result<String> = withContext(Dispatchers.IO) {
        // одна повторная попытка: мобильные сети иногда рвут TLS-хендшейк
        var last: Result<String> = Result.failure(IllegalStateException("не запускалось"))
        for (attempt in 1..2) {
            last = validateOnce()
            if (last.isSuccess) return@withContext last
            if (attempt == 1) delay(700)
        }
        last
    }

    private suspend fun validateOnce(): Result<String> = withContext(Dispatchers.IO) {
        try {
            fastClient.newCall(Request.Builder().url(url("getMe")).build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.code == 404) {
                    Result.failure(IllegalArgumentException(
                        "Токен не похож на правильный (404). Формат: 123456789:AAHfqT… — проверь у @BotFather"))
                } else if (resp.code == 401) {
                    Result.failure(IllegalArgumentException(
                        "Токен неверный (401): Telegram его не принял. Проверь, что скопирован целиком, или создай новый через /newbot"))
                } else if (!resp.isSuccessful) {
                    Result.failure(IllegalStateException("Telegram ответил ошибкой HTTP ${resp.code}"))
                } else {
                    parse(text) { root ->
                        Result.success(root.getAsJsonObject("result").get("username").asString)
                    }
                }
            }
        } catch (e: java.io.IOException) {
            Result.failure(IllegalStateException(
                "Нет связи с api.telegram.org (${e.javaClass.simpleName}) — проверь интернет; если в сети провайдера Telegram API блокируется, попробуйте мобильный интернет или VPN"))
        } catch (e: Throwable) {
            Result.failure(IllegalStateException(
                "Не удалось проверить токен: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    /** Long poll. [timeoutSec] up to 50; returns messages, callbacks and the next offset. */
    suspend fun getUpdates(
        offset: Long,
        timeoutSec: Int = 25,
    ): Triple<List<TgMessage>, List<TgCallback>, Long> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("${url("getUpdates")}?timeout=$timeoutSec&offset=$offset" +
                    "&allowed_updates=%5B%22message%22,%22callback_query%22%5D")
                .build()
            pollClient.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("getUpdates HTTP ${resp.code}")
                parse(text) { root ->
                    val messages = mutableListOf<TgMessage>()
                    val callbacks = mutableListOf<TgCallback>()
                    var maxId = if (offset > 0) offset - 1 else 0L
                    root.getAsJsonArray("result")?.forEach { element ->
                        val update = element.asJsonObject
                        val updateId = update.get("update_id").asLong
                        if (updateId > maxId) maxId = updateId

                        update.getAsJsonObject("message")?.let { message ->
                            val chat = message.getAsJsonObject("chat") ?: return@forEach
                            val kind = when {
                                message.get("text")?.isJsonNull == false -> ""
                                message.get("photo")?.isJsonNull == false -> "фото"
                                message.get("sticker")?.isJsonNull == false -> "стикер"
                                message.get("voice")?.isJsonNull == false -> "голосовое"
                                message.get("video_note")?.isJsonNull == false -> "видеосообщение"
                                message.get("video")?.isJsonNull == false -> "видео"
                                message.get("animation")?.isJsonNull == false -> "гифка"
                                message.get("document")?.isJsonNull == false -> "файл"
                                message.get("audio")?.isJsonNull == false -> "аудио"
                                message.get("location")?.isJsonNull == false -> "геолокация"
                                message.get("contact")?.isJsonNull == false -> "контакт"
                                else -> "вложение"
                            }
                            val photoId = if (kind == "фото")
                                message.getAsJsonArray("photo")?.lastOrNull()
                                    ?.asJsonObject?.get("file_id")?.asString ?: ""
                            else ""
                            messages.add(
                                TgMessage(
                                    updateId = updateId,
                                    chatId = chat.get("id").asLong,
                                    text = message.get("text")?.asString ?: "",
                                    firstName = message.getAsJsonObject("from")
                                        ?.get("first_name")?.asString ?: "",
                                    kind = kind,
                                    photoId = photoId,
                                )
                            )
                            return@forEach
                        }

                        update.getAsJsonObject("callback_query")?.let { cb ->
                            val message = cb.getAsJsonObject("message")
                            val chat = message?.getAsJsonObject("chat")
                            if (chat != null) {
                                callbacks.add(
                                    TgCallback(
                                        updateId = updateId,
                                        callbackId = cb.get("id").asString,
                                        chatId = chat.get("id").asLong,
                                        messageId = message?.get("message_id")?.asInt ?: 0,
                                        data = cb.get("data")?.asString ?: "",
                                        firstName = cb.getAsJsonObject("from")
                                            ?.get("first_name")?.asString ?: "",
                                    )
                                )
                            }
                        }
                    }
                    Triple(messages, callbacks, maxId + 1)
                }
            }
        }

    suspend fun sendMessage(
        chatId: Long,
        text: String,
        keyboard: List<String> = emptyList(),
        inlineMenu: List<InlineBtn> = emptyList(),
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
        try {
            val payloadMap = mutableMapOf<String, Any>(
                "chat_id" to chatId, "text" to text.take(4000))
            if (keyboard.isNotEmpty()) {
                val rows = keyboard.chunked(2).map { row -> row.toList() }
                payloadMap["reply_markup"] = com.google.gson.Gson().toJson(
                    mapOf("keyboard" to rows, "resize_keyboard" to true))
            }
            if (inlineMenu.isNotEmpty()) {
                payloadMap["reply_markup"] = com.google.gson.Gson().toJson(
                    mapOf("inline_keyboard" to inlineMenu.layoutRows().map { r -> r.map { it.toApi() } }))
            }
            val payload = com.google.gson.Gson().toJson(payloadMap).toRequestBody(json)
            fastClient.newCall(Request.Builder().url(url("sendMessage")).post(payload).build())
                .execute().use { resp ->
                    if (resp.isSuccessful) Result.success(Unit)
                    else Result.failure(IllegalStateException("sendMessage HTTP ${resp.code}"))
                }
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /** file_path файла по его file_id (для скачивания). */
    suspend fun getFile(fileId: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val payload = Gson().toJson(mapOf("file_id" to fileId)).toRequestBody(json)
            fastClient.newCall(Request.Builder().url(url("getFile")).post(payload).build())
                .execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("getFile HTTP ${resp.code}")
                    Result.success(
                        parse(text) { it.get("result")?.asJsonObject?.get("file_path")?.asString ?: "" }
                    )
                }
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /** Скачивает файл Telegram в указанную директорию. */
    suspend fun downloadFile(filePath: String, targetDir: java.io.File, name: String): Result<java.io.File> =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("https://api.telegram.org/file/bot$token/$filePath")
                    .build()
                fastClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("download HTTP ${resp.code}")
                    val out = java.io.File(targetDir, name)
                    resp.body!!.byteStream().use { ins ->
                        out.outputStream().use { ins.copyTo(it) }
                    }
                    Result.success(out)
                }
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }

    /** Фото в чат/канал (файл с диска). Возвращает message_id для удаления. */
    suspend fun sendPhoto(
        chatId: Long,
        image: java.io.File,
        caption: String = "",
        urlButton: InlineBtn? = null,
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val mime = when (image.extension.lowercase()) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                else -> "image/jpeg"
            }.toMediaType()
            val builder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId.toString())
                .addFormDataPart("caption", caption.take(1000))
                .addFormDataPart("photo", image.name, image.asRequestBody(mime))
            if (urlButton != null) {
                val kb = com.google.gson.Gson().toJson(
                    mapOf("inline_keyboard" to listOf(listOf(mapOf(
                        "text" to urlButton.label, "url" to urlButton.url)))))
                builder.addFormDataPart("reply_markup", kb)
            }
            fastClient.newCall(Request.Builder().url(url("sendPhoto")).post(builder.build()).build())
                .execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("sendPhoto HTTP ${resp.code}: $text")
                    Result.success(
                        parse(text) {
                            it.getAsJsonObject("result")?.get("message_id")?.asInt ?: 0
                        }
                    )
                }
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /** Сообщение в канал по имени «@channel» (chat_id строкой). */
    suspend fun sendTo(
        channel: String,
        text: String,
        inlineMenu: List<InlineBtn> = emptyList(),
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val payloadMap = mutableMapOf<String, Any>(
                "chat_id" to channel, "text" to text.take(4000))
            if (inlineMenu.isNotEmpty()) {
                payloadMap["reply_markup"] = com.google.gson.Gson().toJson(
                    mapOf("inline_keyboard" to inlineMenu.layoutRows().map { r -> r.map { it.toApi() } }))
            }
            val payload = Gson().toJson(payloadMap).toRequestBody(json)
            fastClient.newCall(Request.Builder().url(url("sendMessage")).post(payload).build())
                .execute().use { resp ->
                    if (resp.isSuccessful) Result.success(Unit)
                    else Result.failure(IllegalStateException("sendMessage HTTP ${resp.code}"))
                }
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /** Фото в канал по имени «@channel» (chat_id строкой). */
    suspend fun sendPhotoTo(
        channel: String,
        image: java.io.File,
        caption: String = "",
        urlButton: InlineBtn? = null,
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val mime = when (image.extension.lowercase()) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                else -> "image/jpeg"
            }.toMediaType()
            val builder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", channel)
                .addFormDataPart("caption", caption.take(1000))
                .addFormDataPart("photo", image.name, image.asRequestBody(mime))
            if (urlButton != null) {
                val kb = com.google.gson.Gson().toJson(
                    mapOf("inline_keyboard" to listOf(listOf(mapOf(
                        "text" to urlButton.label, "url" to urlButton.url)))))
                builder.addFormDataPart("reply_markup", kb)
            }
            fastClient.newCall(Request.Builder().url(url("sendPhoto")).post(builder.build()).build())
                .execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("sendPhoto HTTP ${resp.code}: $text")
                    Result.success(
                        parse(text) {
                            it.getAsJsonObject("result")?.get("message_id")?.asInt ?: 0
                        }
                    )
                }
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /** Удалить сообщение (например, объявление из канала). */
    suspend fun deleteMessage(chatId: String, messageId: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val payload = Gson().toJson(
                    mapOf("chat_id" to chatId, "message_id" to messageId)).toRequestBody(json)
                fastClient.newCall(Request.Builder().url(url("deleteMessage")).post(payload).build())
                    .execute().use { resp ->
                        // «message to delete not found» — не ошибка для нас.
                        if (resp.code == 400) Result.success(Unit)
                        else if (resp.isSuccessful) Result.success(Unit)
                        else Result.failure(IllegalStateException("deleteMessage HTTP ${resp.code}"))
                    }
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }

    /** Показать «печатает…» в чате. Ошибки молча игнорируем — не критично. */
    suspend fun sendChatAction(chatId: Long, action: String = "typing"): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val payload = Gson().toJson(
                    mapOf("chat_id" to chatId, "action" to action)).toRequestBody(json)
                fastClient.newCall(Request.Builder().url(url("sendChatAction")).post(payload).build())
                    .execute().use { resp ->
                        if (resp.isSuccessful) Result.success(Unit)
                        else Result.failure(IllegalStateException("HTTP ${resp.code}"))
                    }
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }

    /** Всплывающий ответ на нажатие inline-кнопки (убирает «часики»). */
    suspend fun answerCallbackQuery(callbackId: String, text: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val payloadMap = mutableMapOf<String, Any>("callback_query_id" to callbackId)
                if (text.isNotBlank()) payloadMap["text"] = text.take(180)
                val payload = Gson().toJson(payloadMap).toRequestBody(json)
                fastClient.newCall(Request.Builder().url(url("answerCallbackQuery")).post(payload).build())
                    .execute().use { resp ->
                        if (resp.isSuccessful) Result.success(Unit)
                        else Result.failure(IllegalStateException("HTTP ${resp.code}"))
                    }
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }

    /** Отредактировать текст своего сообщения (сохраняя inline-кнопки). */
    suspend fun editMessageText(
        chatId: Long,
        messageId: Int,
        text: String,
        inlineMenu: List<InlineBtn> = emptyList(),
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val payloadMap = mutableMapOf<String, Any>(
                "chat_id" to chatId,
                "message_id" to messageId,
                "text" to text.take(4000),
            )
            if (inlineMenu.isNotEmpty()) {
                payloadMap["reply_markup"] = Gson().toJson(
                    mapOf("inline_keyboard" to inlineMenu.layoutRows().map { r -> r.map { it.toApi() } }))
            }
            val payload = Gson().toJson(payloadMap).toRequestBody(json)
            fastClient.newCall(Request.Builder().url(url("editMessageText")).post(payload).build())
                .execute().use { resp ->
                    if (resp.isSuccessful) Result.success(Unit)
                    else Result.failure(IllegalStateException("editMessageText HTTP ${resp.code}"))
                }
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /**
     * Задать список команд для кнопки «Меню» в Telegram (setMyCommands).
     * Команды нормализуем: без «/», в нижний регистр, до 32 символов.
     */
    suspend fun setMyCommands(commands: List<Pair<String, String>>): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                // Telegram: command = ^[a-z0-9_]{1,32}$, description = НЕпустое 1..256,
                // команды без дубликатов — иначе HTTP 400.
                val valid = Regex("^[a-z0-9_]{1,32}$")
                val cleaned = commands
                    .mapNotNull { (raw, desc) ->
                        val c = raw.removePrefix("/").trim().lowercase()
                            .replace(Regex("[^a-z0-9_]"), "")
                        if (!valid.matches(c)) null
                        else c to desc.trim().ifBlank { "Команда /$c" }
                    }
                    .distinctBy { it.first }
                    .take(20)
                if (cleaned.isEmpty()) return@withContext Result.failure(
                    IllegalStateException("нет корректных команд (только a-z, 0-9, _)"))
                val cmds = cleaned.map { (c, d) ->
                    mapOf("command" to c, "description" to d.take(256))
                }
                val payload = Gson().toJson(mapOf("commands" to cmds)).toRequestBody(json)
                fastClient.newCall(Request.Builder().url(url("setMyCommands")).post(payload).build())
                    .execute().use { resp ->
                        if (resp.isSuccessful) {
                            Result.success(Unit)
                        } else {
                            val body = resp.body?.string().orEmpty()
                            val tgReason = runCatching {
                                JsonParser.parseString(body).asJsonObject
                                    .get("description")?.asString
                            }.getOrNull()
                            Result.failure(IllegalStateException(
                                "setMyCommands HTTP ${resp.code}" +
                                    (tgReason?.let { ": $it" } ?: "")))
                        }
                    }
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }

    /** Removes a webhook if one was set (no-op otherwise). Unlocks getUpdates. */
    suspend fun deleteWebhook(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            fastClient.newCall(Request.Builder().url(url("deleteWebhook")).build()).execute().use { resp ->
                if (resp.isSuccessful) Result.success(Unit)
                else Result.failure(IllegalStateException("deleteWebhook HTTP ${resp.code}"))
            }
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /** Кнопка в формате Bot API: ссылка или callback_data (id обязан быть непустым). */
    private fun InlineBtn.toApi(): Map<String, String> = when {
        url.isNotBlank() -> mapOf("text" to label, "url" to url)
        id.isNotBlank() -> mapOf("text" to label, "callback_data" to id)
        else -> mapOf("text" to label, "callback_data" to ("cb" + label.hashCode()))
    }
}
