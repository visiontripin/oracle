package com.botcontrol.admin.data

import android.content.Context
import java.io.File

/**
 * Файлы бота: у каждого бота своя директория внутри приложения:
 * bot_<id>/media — картинки и медиа, bot_<id>/scripts — JS-скрипты,
 * bot_<id>/docs — документы. Сюда кладут файлы HTTP-сервер и импорт.
 */
object BotFiles {
    const val MEDIA = "media"
    const val SCRIPTS = "scripts"
    const val DOCS = "docs"
    val kinds = listOf(MEDIA, SCRIPTS, DOCS)

    fun botDir(ctx: Context, botId: Long): File =
        File(ctx.filesDir, "bot_$botId").apply { mkdirs() }

    fun subdir(ctx: Context, botId: Long, kind: String): File =
        File(botDir(ctx, botId), kind).apply { mkdirs() }

    fun list(ctx: Context, botId: Long, kind: String): List<File> =
        subdir(ctx, botId, kind).listFiles()?.filter { it.isFile }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()

    /** Убирает пути и запрещённые символы из имени файла. */
    fun sanitizeName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\u0000-\u001f]"), "_")
            .trim('.', ' ').take(120)

    /** Файл в директории бота с защитой от выхода за её пределы. */
    fun safe(ctx: Context, botId: Long, kind: String, name: String): File? {
        val clean = sanitizeName(name)
        if (clean.isBlank()) return null
        val root = subdir(ctx, botId, kind).canonicalFile
        val f = File(root, clean).canonicalFile
        return if (f.path.startsWith(root.path)) f else null
    }

    fun allBotIds(ctx: Context): List<Long> =
        ctx.filesDir.listFiles()?.mapNotNull {
            it.name.removePrefix("bot_").toLongOrNull()
        }?.sorted() ?: emptyList()
}
