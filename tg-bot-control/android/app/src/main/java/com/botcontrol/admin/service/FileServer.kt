package com.botcontrol.admin.service

import android.content.Context
import com.botcontrol.admin.data.BotFiles
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream

/**
 * Файловый сервер приложения (NanoHTTPD, порт 8090): страница в локальной
 * Wi-Fi сети для загрузки и скачивания файлов ботов. Файлы попадают в
 * директорию бота: media / scripts / docs. Доступ без пароля — только
 * локальная сеть, поэтому сервер включается вручную и гасится сам.
 */
class FileServer(context: Context) : NanoHTTPD(PORT) {

    private val appContext = context.applicationContext

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.orEmpty()
        return try {
            when {
                session.method == Method.POST && uri == "/upload" -> upload(session)
                uri == "/delete" -> delete(session)
                uri.startsWith("/file/") -> download(uri.removePrefix("/file/"))
                else -> page(session)
            }
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "text/plain; charset=utf-8",
                "Ошибка: ${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    // ---------- страница ----------

    private fun page(session: IHTTPSession): Response {
        val bot = session.parameters["bot"]?.firstOrNull()?.toLongOrNull() ?: 1L
        val kind = session.parameters["kind"]?.firstOrNull()
            ?.takeIf { it in BotFiles.kinds } ?: BotFiles.MEDIA

        val sb = StringBuilder()
        sb.append("<html><head><meta charset='utf-8'>")
        sb.append("<meta name='viewport' content='width=device-width, initial-scale=1'>")
        sb.append("<title>BotControl — файлы ботов</title>")
        sb.append("<style>body{font-family:sans-serif;background:#0E1621;color:#F5F6F7;padding:16px}")
        sb.append("a{color:#5288C1}h3{margin:14px 0 6px}table{border-collapse:collapse;width:100%;max-width:760px}")
        sb.append("td,th{border:1px solid #33475A;padding:6px;text-align:left;font-size:14px}")
        sb.append(".tab{display:inline-block;padding:4px 10px;margin:2px;border-radius:8px;background:#17212B}")
        sb.append(".on{background:#2B5278}.btn{color:#F5F6F7;background:#2B5278;padding:3px 10px;border-radius:6px;text-decoration:none}")
        sb.append("</style></head><body>")
        sb.append("<h2>📦 Файлы ботов BotControl</h2>")
        sb.append("<p style='color:#7F91A4'>Загружайте файлы с компьютера или телефона в той же Wi-Fi сети. ")
        sb.append("media — картинки и медиа, scripts — JS-скрипты для правил, docs — документы.</p>")

        val botIds = BotFiles.allBotIds(appContext).ifEmpty { listOf(1L) }
        sb.append("<h3>Бот:</h3>")
        botIds.forEach { id ->
            val on = if (id == bot) " tab on" else " tab"
            sb.append("<a class='$on' href='/?bot=$id&amp;kind=$kind'>Бот $id</a>")
        }
        sb.append("<h3>Папка:</h3>")
        BotFiles.kinds.forEach { k ->
            val on = if (k == kind) " tab on" else " tab"
            sb.append("<a class='$on' href='/?bot=$bot&amp;kind=$k'>$k</a>")
        }

        sb.append("<h3>Файлы (${BotFiles.list(appContext, bot, kind).size}):</h3>")
        sb.append("<table><tr><th>Имя</th><th>Размер</th><th></th><th></th></tr>")
        BotFiles.list(appContext, bot, kind).forEach { f ->
            val name = esc(f.name)
            val url = java.net.URLEncoder.encode(f.name, "UTF-8")
            sb.append("<tr><td>$name</td><td>${f.length() / 1024} КБ</td>")
            sb.append("<td><a class='btn' href='/file/$bot/$kind/$url'>скачать</a></td>")
            sb.append("<td><a class='btn' href='/delete?bot=$bot&amp;kind=$kind&amp;name=$url'>удалить</a></td></tr>")
        }
        sb.append("</table>")

        sb.append("<h3>Загрузить (до 3 файлов):</h3>")
        sb.append("<form method='post' action='/upload' enctype='multipart/form-data'>")
        sb.append("<input type='hidden' name='bot' value='$bot'><input type='hidden' name='kind' value='$kind'>")
        for (n in 1..3) {
            sb.append("<p><input type='file' name='file$n'> ")
            sb.append("<input type='text' name='name$n' placeholder='имя (необязательно)' size='22'></p>")
        }
        sb.append("<p><button class='btn' type='submit'>Загрузить</button></p></form>")
        sb.append("</body></html>")

        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", sb.toString())
    }

    // ---------- действия ----------

    private fun upload(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val bot = session.parameters["bot"]?.firstOrNull()?.toLongOrNull() ?: 1L
        val kind = session.parameters["kind"]?.firstOrNull()
            ?.takeIf { it in BotFiles.kinds } ?: BotFiles.MEDIA

        var saved = 0
        for (n in 1..3) {
            val tmp = files["file$n"] ?: continue
            if (tmp.isBlank()) continue
            // NanoHTTPD кладёт оригинальное имя файла в parameters.
            val original = session.parameters["file$n"]?.firstOrNull().orEmpty()
            val custom = session.parameters["name$n"]?.firstOrNull()?.trim().orEmpty()
            val name = custom.ifBlank { original }.ifBlank { "file_$n.bin" }
            val target = BotFiles.safe(appContext, bot, kind, name) ?: continue
            runCatching {
                File(tmp).copyTo(target, overwrite = true)
                saved++
            }
        }
        return redirect("/?bot=$bot&kind=$kind" + if (saved > 0) "&ok=$saved" else "")
    }

    private fun delete(session: IHTTPSession): Response {
        val bot = session.parameters["bot"]?.firstOrNull()?.toLongOrNull() ?: 1L
        val kind = session.parameters["kind"]?.firstOrNull()
            ?.takeIf { it in BotFiles.kinds } ?: BotFiles.MEDIA
        val name = session.parameters["name"]?.firstOrNull().orEmpty()
        BotFiles.safe(appContext, bot, kind, name)?.delete()
        return redirect("/?bot=$bot&kind=$kind")
    }

    private fun download(path: String): Response {
        val parts = path.split("/").map { it.trim() }
        if (parts.size != 3) return notFound()
        val bot = parts[0].toLongOrNull() ?: return notFound()
        val kind = parts[1]
        if (kind !in BotFiles.kinds) return notFound()
        val name = java.net.URLDecoder.decode(parts[2], "UTF-8")
        val f = BotFiles.safe(appContext, bot, kind, name) ?: return notFound()
        val mime = when (f.extension.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp3", "ogg" -> "audio/mpeg"
            "mp4" -> "video/mp4"
            "pdf" -> "application/pdf"
            "json" -> "application/json"
            else -> "text/plain; charset=utf-8"
        }
        val resp = newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(f), f.length())
        resp.addHeader("Content-Disposition", "attachment; filename=\"${f.name}\"")
        return resp
    }

    // ---------- утилиты ----------

    private fun redirect(to: String): Response {
        val r = newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "")
        r.addHeader("Location", to)
        return r
    }

    private fun notFound(): Response =
        newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=utf-8", "Не найдено")

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;")

    companion object {
        const val PORT = 8090

        /** Локальный IPv4-адрес телефона в Wi-Fi — для ссылки в интерфейсе. */
        fun lanAddress(): String? =
            java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull {
                    it is java.net.Inet4Address && !it.isLoopbackAddress &&
                        it.isSiteLocalAddress
                }?.hostAddress
    }
}
