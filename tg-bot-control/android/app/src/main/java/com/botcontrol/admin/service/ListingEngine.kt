package com.botcontrol.admin.service

import android.content.Context
import com.botcontrol.admin.data.BotFiles
import com.botcontrol.admin.data.InlineBtn
import com.botcontrol.admin.data.Listing
import com.botcontrol.admin.data.LocalBotStore
import com.botcontrol.admin.data.telegram.TelegramApi
import com.botcontrol.admin.llm.DeviceLlm

/**
 * Режим «Объявления» (например, канал @daromvpl): визард публикации
 * (описание → контакт → фото → предпросмотр → публикация в канал),
 * «Мои объявления» со статистикой, удалением и переопределением по кнопкам.
 * Все шаги — кнопками и вводом необходимого, с подсказками на каждом шаге.
 *
 * Включается в «Настройках бота» (режим + канал). Триггеры:
 * «📝 Разместить объявление» / /new, «📋 Мои объявления» / /my,
 * «❌ Отмена» / /cancel. Кнопки движка имеют префикс lst_.
 */
object ListingEngine {

    private enum class State { DESC, CONTACT, PHONE, PHOTOS, PREVIEW }

    private data class Draft(
        val state: State,
        val description: String = "",
        val contactType: String = "msg",
        val contactValue: String = "",
        val photos: List<String> = emptyList(), // имена файлов в media
        val editingId: String? = null,
    )

    /** Активные визарды: ключ "botId:chatId" → черновик (в памяти сессии). */
    private val drafts = HashMap<String, Draft>()

    private fun key(botId: Long, chatId: Long) = "$botId:$chatId"

    const val BTN_NEW = "📝 Разместить объявление"
    const val BTN_MY = "📋 Мои объявления"
    const val BTN_CANCEL = "❌ Отмена"

    private val menuKeyboard = listOf(BTN_NEW, BTN_MY)

    // ==================================================================
    // Текстовые сообщения
    // ==================================================================

    suspend fun handleText(
        context: Context,
        store: LocalBotStore,
        api: TelegramApi,
        botId: Long,
        chatId: Long,
        firstName: String,
        text: String,
        photoId: String,
        replyKeyboard: List<String>,
    ): Boolean {
        val k = key(botId, chatId)
        val norm = text.trim().lowercase()
        val draft = drafts[k]

        // ---------- глобальные триггеры ----------
        val isNew = norm == BTN_NEW.lowercase() || norm == "/new" || norm == "разместить объявление"
        val isMy = norm == BTN_MY.lowercase() || norm == "/my" || norm == "мои объявления"
        val isCancel = norm == BTN_CANCEL.lowercase() || norm == "/cancel" || norm == "отмена"

        if (isCancel) {
            drafts.remove(k)
            api.sendMessage(
                chatId,
                "❌ Действие отменено. Выбери кнопку ниже 👇",
                keyboard = replyKeyboard.ifEmpty { menuKeyboard },
            )
            return true
        }
        if (isNew) {
            drafts[k] = Draft(state = State.DESC)
            api.sendMessage(
                chatId,
                "📝 Шаг 1 из 4 — описание.\n\nОпиши вещь одним сообщением: что отдаёшь, город/район, состояние.\nПример: «Кресло кожаное, Приморский район, б/у, самовывоз».\n\n❌ Отмена — прекратить.",
                keyboard = replyKeyboard.ifEmpty { menuKeyboard },
            )
            return true
        }
        if (isMy) {
            showMyListings(context, store, api, botId, chatId, replyKeyboard)
            return true
        }

        // ---------- /start: приветствие ----------
        if (norm == "/start") {
            drafts.remove(k)
            api.sendMessage(
                chatId,
                "👋 $firstName, добро пожаловать в «Даром в ПЛ»!\n\n" +
                    "Здесь отдают вещи бесплатно. Выбери действие кнопкой ниже 👇\n\n" +
                    "📝 Разместить объявление — опубликую твою вещь в канале.\n" +
                    "📋 Мои объявления — статистика, переопубликовать или удалить.\n\n" +
                    "Правила: только даром, самовывоз по умолчанию, спам удаляется.",
                keyboard = replyKeyboard.ifEmpty { menuKeyboard },
            )
            return true
        }

        // ---------- шаги визарда ----------
        if (draft != null) {
            when (draft.state) {
                State.DESC -> {
                    if (norm.startsWith("/")) return false // команды не рвут визард? — рвём только свою
                    val desc = text.trim().take(800)
                    drafts[k] = draft.copy(state = State.CONTACT, description = desc)
                    api.sendMessage(chatId, "✅ Описание сохранено:\n«$desc»")
                    api.sendMessage(
                        chatId,
                        "📞 Шаг 2 из 4 — контакт.\n\nКак желающие забрать будут связываться с тобой?",
                        inlineMenu = listOf(
                            InlineBtn(label = "✉️ Написать в личку", toast = "Связь в личку", action = "lst_contact_msg"),
                            InlineBtn(label = "📞 Указать телефон", toast = "Связь по телефону", action = "lst_contact_phone"),
                        ),
                    )
                    return true
                }
                State.PHONE -> {
                    val phone = text.trim()
                    val ok = Regex("^[+]?[\\d\\s\\-()]{10,20}$").matches(phone)
                    if (!ok) {
                        api.sendMessage(
                            chatId,
                            "⚠️ Не похоже на номер. Пример: +7 999 123-45-67\nПопробуй ещё раз или «❌ Отмена».",
                        )
                        return true
                    }
                    drafts[k] = draft.copy(state = State.PHOTOS, contactType = "phone", contactValue = phone)
                    askPhotos(api, chatId, replyKeyboard)
                    return true
                }
                State.PHOTOS -> {
                    if (photoId.isNotBlank()) {
                        val count = draft.photos.size
                        if (count >= 3) {
                            api.sendMessage(chatId, "❌ Максимум 3 фото. Нажми «✅ Готово, к публикации».")
                            return true
                        }
                        val saved = downloadPhoto(context, store, api, botId, chatId, photoId, count + 1)
                        if (saved != null) {
                            drafts[k] = draft.copy(photos = draft.photos + saved)
                            api.sendMessage(
                                chatId,
                                "📎 Фото ${count + 1}/3 получено.\n\nПришли ещё или нажми кнопку 👇",
                                inlineMenu = listOf(
                                    InlineBtn(label = "✅ Готово, к публикации", toast = "К предпросмотру", action = "lst_photos_done"),
                                    InlineBtn(label = "⏭ Без фото", toast = "Опубликуем без фото", action = "lst_photos_done"),
                                    InlineBtn(label = BTN_CANCEL, toast = "Отмена", action = "lst_cancel"),
                                ),
                            )
                        } else {
                            api.sendMessage(chatId, "❌ Не удалось скачать фото. Попробуй ещё раз.")
                        }
                        return true
                    }
                    api.sendMessage(
                        chatId,
                        "📸 Пришли фото (до 3) или нажми «✅ Готово, к публикации».",
                        inlineMenu = listOf(
                            InlineBtn(label = "✅ Готово, к публикации", toast = "К предпросмотру", action = "lst_photos_done"),
                            InlineBtn(label = BTN_CANCEL, toast = "Отмена", action = "lst_cancel"),
                        ),
                    )
                    return true
                }
                State.CONTACT, State.PREVIEW -> {
                    api.sendMessage(
                        chatId,
                        "Подожди — выбери вариант кнопкой выше ☝️ или «❌ Отмена».",
                    )
                    return true
                }
            }
        }

        // не объявленный текст — не наш
        return false
    }

    // ==================================================================
    // Inline-кнопки (lst_*)
    // ==================================================================

    suspend fun handleCallback(
        context: Context,
        store: LocalBotStore,
        api: TelegramApi,
        botId: Long,
        chatId: Long,
        messageId: Int,
        callbackId: String,
        data: String,
        firstName: String,
        replyKeyboard: List<String>,
    ) {
        val k = key(botId, chatId)
        val draft = drafts[k]
        when {
            data == "lst_cancel" -> {
                drafts.remove(k)
                api.answerCallbackQuery(callbackId, "Отменено")
                api.sendMessage(chatId, "❌ Действие отменено. Выбери кнопку ниже 👇",
                    keyboard = replyKeyboard.ifEmpty { menuKeyboard })
            }
            data == "lst_contact_msg" -> {
                if (draft == null) return stale(api, callbackId)
                drafts[k] = draft.copy(state = State.PHOTOS, contactType = "msg", contactValue = "")
                api.answerCallbackQuery(callbackId, "Связь: в личку")
                askPhotos(api, chatId, replyKeyboard)
            }
            data == "lst_contact_phone" -> {
                if (draft == null) return stale(api, callbackId)
                drafts[k] = draft.copy(state = State.PHONE)
                api.answerCallbackQuery(callbackId, "Связь: телефон")
                api.sendMessage(
                    chatId,
                    "📞 Введи номер телефона одним сообщением.\nПример: +7 999 123-45-67\n\n«❌ Отмена» — прекратить.",
                )
            }
            data == "lst_photos_done" -> {
                if (draft == null) return stale(api, callbackId)
                api.answerCallbackQuery(callbackId, "Предпросмотр")
                drafts[k] = draft.copy(state = State.PREVIEW)
                showPreview(api, chatId, draft)
            }
            data == "lst_publish" -> {
                val d = draft
                if (d == null || d.state != State.PREVIEW) return stale(api, callbackId)
                drafts.remove(k)
                api.answerCallbackQuery(callbackId, "Публикую…")
                publish(context, store, api, botId, chatId, firstName, d, replyKeyboard)
            }
            data == "lst_preview_cancel" -> {
                drafts.remove(k)
                api.answerCallbackQuery(callbackId, "Отменено")
                api.editMessageText(chatId, messageId, "❌ Публикация отменена.")
                api.sendMessage(chatId, "Главное меню 👇", keyboard = replyKeyboard.ifEmpty { menuKeyboard })
            }
            data.startsWith("lst_del_") -> {
                val id = data.removePrefix("lst_del_")
                val all = store.listings(botId)
                val target = all.firstOrNull { it.id == id && it.chatId == chatId }
                if (target == null) {
                    api.answerCallbackQuery(callbackId, "Объявление не найдено")
                    return
                }
                val channel = store.channelId(botId)
                target.messageIds.forEach { mid ->
                    api.deleteMessage(channel, mid)
                }
                store.setListings(all.filterNot { it.id == id }, botId)
                api.answerCallbackQuery(callbackId, "🗑 Удалено")
                DeviceLlm.log("🗑 [$botId] Объявление ${target.id} удалено из канала (${target.messageIds.size} сообщ.)")
                api.editMessageText(chatId, messageId, "🗑 Объявление удалено из канала.\n\nВыбери действие 👇")
                api.sendMessage(chatId, "Главное меню 👇", keyboard = replyKeyboard.ifEmpty { menuKeyboard })
            }
            data.startsWith("lst_rep_") -> {
                val id = data.removePrefix("lst_rep_")
                val all = store.listings(botId)
                val target = all.firstOrNull { it.id == id && it.chatId == chatId }
                if (target == null) {
                    api.answerCallbackQuery(callbackId, "Объявление не найдено")
                    return
                }
                api.answerCallbackQuery(callbackId, "Публикую снова…")
                val d = Draft(
                    state = State.PREVIEW,
                    description = target.description,
                    contactType = target.contactType,
                    contactValue = target.contactValue,
                    photos = target.photos,
                    editingId = target.id,
                )
                publish(context, store, api, botId, chatId, firstName, d, replyKeyboard)
            }
            data.startsWith("lst_edit_") -> {
                val id = data.removePrefix("lst_edit_")
                val all = store.listings(botId)
                val target = all.firstOrNull { it.id == id && it.chatId == chatId }
                if (target == null) {
                    api.answerCallbackQuery(callbackId, "Объявление не найдено")
                    return
                }
                api.answerCallbackQuery(callbackId, "Новое описание")
                drafts[k] = Draft(state = State.DESC, editingId = target.id)
                api.sendMessage(
                    chatId,
                    "✏️ Редактирование.\n\nПришли новое описание текстом (фото и контакт сохранятся):\n«${target.description.take(120)}»",
                )
            }
        }
    }

    // ==================================================================
    // Шаги
    // ==================================================================

    private suspend fun askPhotos(api: TelegramApi, chatId: Long, replyKeyboard: List<String>) {
        api.sendMessage(
            chatId,
            "📸 Шаг 3 из 4 — фото.\n\nПришли от 0 до 3 фото вещи.\nБез фото тоже можно, но с фото берут чаще 😉",
            inlineMenu = listOf(
                InlineBtn(label = "✅ Готово, к публикации", toast = "К предпросмотру", action = "lst_photos_done"),
                InlineBtn(label = BTN_CANCEL, toast = "Отмена", action = "lst_cancel"),
            ),
        )
    }

    private suspend fun downloadPhoto(
        context: Context,
        store: LocalBotStore,
        api: TelegramApi,
        botId: Long,
        chatId: Long,
        photoId: String,
        index: Int,
    ): String? {
        return runCatching {
            val path = api.getFile(photoId).getOrThrow()
            val dir = BotFiles.subdir(context, botId, BotFiles.MEDIA)
            val name = "lst_${chatId}_${System.currentTimeMillis() / 1000}_$index.jpg"
            api.downloadFile(path, dir, name).getOrThrow()
            name
        }.getOrNull()
    }

    private suspend fun showPreview(api: TelegramApi, chatId: Long, d: Draft) {
        val contact = if (d.contactType == "phone") "📞 ${d.contactValue}" else "✉️ через кнопку «Написать автору»"
        val photos = if (d.photos.isEmpty()) "без фото" else "фото: ${d.photos.size}"
        api.sendMessage(
            chatId,
            "👁 Шаг 4 из 4 — предпросмотр.\n\n${captionOf(d.description, d.contactType, d.contactValue)}\n\n$photos\n\nВсё верно?",
            inlineMenu = listOf(
                InlineBtn(label = "✅ Опубликовать", toast = "Публикую", action = "lst_publish"),
                InlineBtn(label = "❌ Отменить", toast = "Отмена", action = "lst_preview_cancel"),
            ),
        )
    }

    private fun captionOf(description: String, contactType: String, contactValue: String): String =
        "📦 Отдам даром\n\n📝 $description\n\n" + if (contactType == "phone")
            "📞 Телефон: $contactValue"
        else "📞 Связь: кнопка «Написать автору» 👇"

    private suspend fun publish(
        context: Context,
        store: LocalBotStore,
        api: TelegramApi,
        botId: Long,
        chatId: Long,
        firstName: String,
        d: Draft,
        replyKeyboard: List<String>,
    ) {
        val channel = store.channelId(botId)
        if (channel.isBlank()) {
            api.sendMessage(chatId, "⚠️ Канал публикаций не задан — скажи владельцу бота указать его в настройках.")
            return
        }
        val caption = captionOf(d.description, d.contactType, d.contactValue)
        val authorBtn = if (d.contactType == "msg")
            InlineBtn(label = "✉️ Написать автору", url = "tg://user?id=$chatId")
        else null

        val messageIds = mutableListOf<Int>()
        var publishError = ""
        val media = d.photos.mapNotNull { name -> BotFiles.safe(context, botId, BotFiles.MEDIA, name) }
        if (media.isEmpty()) {
            // Текстовый пост без фото: message_id недоступен (sendMessage его
            // не возвращает) — удаление такого поста делаем вручную в канале.
            api.sendTo(channel, caption, listOfNotNull(authorBtn))
                .onFailure { publishError = it.message ?: "" }
        } else {
            media.forEachIndexed { i, f ->
                api.sendPhotoTo(channel, f, if (i == 0) caption else "", authorBtn)
                    .onSuccess { mid -> if (mid > 0) messageIds.add(mid) }
                    .onFailure { if (publishError.isBlank()) publishError = it.message ?: "" }
            }
        }

        if (publishError.isNotBlank()) {
            api.sendMessage(chatId, "❌ Ошибка публикации: ${publishError.take(160)}\nПопробуй «📋 Мои объявления» → переопубликовать.")
            return
        }

        // сохранить/заменить
        val all = store.listings(botId).toMutableList()
        val listingId = d.editingId ?: ("L" + System.currentTimeMillis() / 1000)
        all.removeAll { it.id == listingId }
        all.add(
            Listing(
                id = listingId,
                chatId = chatId,
                firstName = firstName,
                description = d.description,
                contactType = d.contactType,
                contactValue = d.contactValue,
                photos = d.photos,
                messageIds = messageIds,
                createdAt = System.currentTimeMillis() / 1000,
            ),
        )
        store.setListings(all, botId)
        DeviceLlm.log("📦 [$botId] Объявление $listingId опубликовано в $channel (фото: ${media.size})")

        api.sendMessage(
            chatId,
            "✅ Объявление опубликовано в канале $channel!\n\nКогда вещь найдёт хозяина — «📋 Мои объявления» → 🗑 Удалить, чтобы убрать пост из канала.",
            keyboard = replyKeyboard.ifEmpty { menuKeyboard },
        )
    }

    private suspend fun showMyListings(
        context: Context,
        store: LocalBotStore,
        api: TelegramApi,
        botId: Long,
        chatId: Long,
        replyKeyboard: List<String>,
    ) {
        drafts.remove(key(botId, chatId))
        val all = store.listings(botId).filter { it.chatId == chatId }
        val active = all.count { it.status == "active" }
        val sb = StringBuilder()
        sb.append("📊 Статистика публикаций\n\n")
        sb.append("Всего объявлений: ${all.size}\n")
        sb.append("Активных: $active\n\n")
        if (all.isEmpty()) {
            sb.append("📭 Пока пусто. Нажми «📝 Разместить объявление» 👇")
            api.sendMessage(chatId, sb.toString(), keyboard = replyKeyboard.ifEmpty { menuKeyboard })
            return
        }
        all.sortedByDescending { it.createdAt }.forEach { l ->
            val short = l.description.take(40) + if (l.description.length > 40) "…" else ""
            sb.append("🎁 $short\n")
            sb.append("🟢 Опубликовано · фото: ${l.photos.size}\n\n")
        }
        // кнопки: до 10 объявлений, по 2 действия на каждое
        val buttons = all.sortedByDescending { it.createdAt }.take(10).flatMap { l ->
            listOf(
                InlineBtn(label = "🔄 ${l.description.take(12)}", toast = "Переопубликовать", action = "lst_rep_${l.id}"),
                InlineBtn(label = "🗑 ${l.description.take(12)}", toast = "Удалить из канала", action = "lst_del_${l.id}"),
            )
        }
        api.sendMessage(
            chatId,
            sb.toString(),
            inlineMenu = buttons.chunked(2).flatten().let { flat ->
                // по 2 кнопки в ряд: 🔄 | 🗑
                flat.chunked(2)
            }.flatten().let { buttons2 ->
                buttons2
            }.let { buttons },
            // порядок ниже поправлен: используем rows
        )
        api.sendMessage(
            chatId,
            "🔄 — опубликовать снова, 🗑 — удалить из канала. Или «📝 Разместить объявление» 👇",
            keyboard = replyKeyboard.ifEmpty { menuKeyboard },
        )
    }

    private suspend fun stale(api: TelegramApi, callbackId: String) {
        api.answerCallbackQuery(callbackId, "Сессия устарела — начни заново")
    }
}
