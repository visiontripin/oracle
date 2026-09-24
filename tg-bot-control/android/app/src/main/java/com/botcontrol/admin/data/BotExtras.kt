package com.botcontrol.admin.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Кнопка под конкретным сообщением (inline keyboard в Telegram).
 * Нажатие -> всплывашка [toast] + действие: текст, случайное из набора,
 * вкл/выкл напоминаний (с правкой сообщения, как в оригинале) или скрипт.
 */
data class InlineBtn(
    val id: String = "",
    val label: String = "",
    val toast: String = "",
    val action: String = "text", // text | pack | reminders_on | reminders_off | script | url
    val packId: String = "",
    val text: String = "",
    val script: String = "",
    /** Если задан — кнопка-ссылка (например tg://user?id=… «Написать автору»). */
    val url: String = "",
    /** Ряд под сообщением: 0 — авто (по 2 в ряд, как раньше), 1..N — номер ряда. */
    val row: Int = 0,
    /** true — заменить текст сообщения с кнопкой, а не присылать новое. */
    val edit: Boolean = false,
    /** Всплывашка, если напоминания уже в нужном состоянии («Уже работает»). */
    val toastNoChange: String = "",
) {
    val isUrl: Boolean get() = url.isNotBlank()
}

/**
 * Раскладка кнопок по рядам Telegram. Старые меню (у всех row == 0)
 * раскладываются по 2 в ряд, как раньше; явные ряды — как в коде бота.
 */
fun List<InlineBtn>.layoutRows(): List<List<InlineBtn>> {
    if (isEmpty()) return emptyList()
    if (all { it.row <= 0 }) return chunked(2)
    val explicit = filter { it.row > 0 }.groupBy { it.row }.toSortedMap().values.map { it.toList() }
    val loose = filter { it.row <= 0 }.map { listOf(it) }
    return explicit + loose
}

/** Явные номера рядов 1..N для меню (для редактора и экспорта). */
fun List<InlineBtn>.withExplicitRows(): List<InlineBtn> =
    layoutRows().flatMapIndexed { index, row -> row.map { it.copy(row = index + 1) } }

/** Событие расписания напоминаний. */
data class ScheduleEvent(
    val id: String = "",
    val hour: Int = 9,
    val minute: Int = 0,
    val text: String = "",
    val days: List<Int> = listOf(1, 2, 3, 4, 5, 6, 7), // 1=Пн … 7=Вс
    val enabled: Boolean = true,
    val menu: List<InlineBtn> = emptyList(),
    /** Отправлять в канал публикаций (см. «Настройки бота → Канал»). */
    val toChannel: Boolean = false,
) {
    fun timeLabel(): String = "%02d:%02d".format(hour, minute)

    fun daysLabel(): String = when (days.sorted().joinToString("")) {
        "1234567" -> "ежедневно"
        "12345" -> "будни"
        "67" -> "выходные"
        else -> days.sorted()
            .map { DAY_NAMES.getOrElse(it - 1) { "?" } }
            .joinToString(", ")
    }

    companion object {
        val DAY_NAMES = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
    }
}

/** Именованный набор случайных ответов (шутки, сарказм…). */
data class ReplyPack(
    val id: String = "",
    val name: String = "",
    val items: List<String> = emptyList(),
)

/** Команда главного меню Telegram (кнопка «Меню» слева от поля ввода). */
data class MenuCommand(
    val command: String = "",
    val description: String = "",
)

/** Gson-хелперы для списков в DataStore. */
object BotJson {
    private val gson = Gson()

    fun <T> load(json: String, type: TypeToken<T>, fallback: T): T =
        try {
            gson.fromJson(json, type.type) ?: fallback
        } catch (_: Throwable) {
            fallback
        }

    fun save(value: Any?): String = gson.toJson(value)

    fun stringList(json: String): List<String> =
        load(json, object : TypeToken<List<String>>() {}, emptyList())

    fun scheduleList(json: String): List<ScheduleEvent> =
        load(json, object : TypeToken<List<ScheduleEvent>>() {}, emptyList())

    fun packList(json: String): List<ReplyPack> =
        load(json, object : TypeToken<List<ReplyPack>>() {}, emptyList())

    fun menu(json: String): List<InlineBtn> =
        load(json, object : TypeToken<List<InlineBtn>>() {}, emptyList())

    fun menuCommandList(json: String): List<MenuCommand> =
        load(json, object : TypeToken<List<MenuCommand>>() {}, emptyList())
}

/** Детерминированные id кнопок: пустой callback_data Telegram отвергает
 *  (HTTP 400 — сообщение не доставляется вовсе). id = "cb"+md5(содержимое):
 *  совпадает при отправке и при нажатии без перезаписи хранилища. */
fun List<InlineBtn>.withIds(): List<InlineBtn> = map { b ->
    if (b.id.isNotBlank()) b else b.copy(
        id = "cb" + md8(b.label + "|" + b.action + "|" + b.packId + "|" + b.text))
}

private fun md8(s: String): String =
    java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(8)

/** Объявление пользователя (режим «Объявления», публикация в канал). */
data class Listing(
    val id: String = "",
    val chatId: Long = 0L,
    val firstName: String = "",
    val description: String = "",
    val contactType: String = "msg",  // msg | phone
    val contactValue: String = "",
    /** Имена файлов фото в bot_<id>/media. */
    val photos: List<String> = emptyList(),
    /** id сообщений объявления в канале (для удаления/переопределения). */
    val messageIds: List<Int> = emptyList(),
    val createdAt: Long = 0L,
    val status: String = "active",    // active | taken
)
