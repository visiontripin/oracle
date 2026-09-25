package com.botcontrol.admin.data

import kotlin.random.Random

/**
 * Анимация через редактирование ОДНОГО сообщения (editMessageText):
 * бот присылает первый кадр, затем каждые [AnimSpec.intervalMs] мс правит
 * это же сообщение следующим кадром, в конце — итоговый текст (+ кнопки).
 *
 * Чистый Kotlin (без Android и Gson): кадры строит [Anim.frames], хранится
 * всё строкой [Anim.encode] — в поле `script` правила или кнопки
 * (actionType / action = "anim"). Так логику можно проверять в харнессе.
 */
data class AnimSpec(
    /** Шаблон: см. [Anim.PRESETS]; "custom" — свои кадры (ASCII-арт, флипбук). */
    val preset: String = "spinner",
    /** Подпись/текст для шаблона (спиннер, прогресс, печать, отсчёт…). */
    val text: String = "",
    /** Свои кадры (preset = "custom"). */
    val frames: List<String> = emptyList(),
    /** Пауза между кадрами, мс (ограничивается [Anim.MIN_INTERVAL]..[Anim.MAX_INTERVAL]). */
    val intervalMs: Int = 700,
    /** Сколько раз прокрутить цикл кадров (для цикличных шаблонов и своих кадров). */
    val loops: Int = 1,
    /** Число для обратного отсчёта. */
    val count: Int = 5,
    /** Итоговый текст после анимации (пусто — остаётся последний кадр). */
    val finalText: String = "",
    /** Итог — случайная фраза из набора (слот-машина, «гадание»…). */
    val packId: String = "",
    /** Моноширинный шрифт (ASCII-арт): кадры уходят в <pre>. */
    val mono: Boolean = false,
)

object Anim {

    const val MIN_INTERVAL = 500
    const val MAX_INTERVAL = 3000
    /** Потолок кадров: Telegram не любит частые правки (≈1 в секунду на чат). */
    const val MAX_FRAMES = 60
    /** Разделитель своих кадров в текстовом поле. */
    const val FRAME_SEP = "---"

    data class Preset(
        val id: String,
        val title: String,
        val hint: String,
        val defaultText: String = "",
        val defaultInterval: Int = 700,
        val cyclic: Boolean = true,
        val mono: Boolean = false,
    )

    val PRESETS: List<Preset> = listOf(
        Preset("spinner", "⠋ Спиннер", "Вращающийся индикатор рядом с подписью", "Загрузка", 600),
        Preset("progress", "▰ Прогресс-бар", "Полоса 0→100 %", "Подготовка", 600, cyclic = false),
        Preset("dots", "… Точки", "Подпись с бегущими точками", "Думаю", 600),
        Preset("countdown", "⏳ Отсчёт", "Обратный отсчёт N…1, затем итог", "Старт через", 1000, cyclic = false),
        Preset("typewriter", "⌨️ Печатная машинка", "Текст появляется по буквам", "Сообщение от системы…", 500, cyclic = false),
        Preset("matrix", "🟩 Матрица", "Цифровой дождь (моноширинный)", "", 600, mono = true),
        Preset("slot", "🎰 Слот-машина", "Барабаны крутятся, итог — из набора или текста", "", 500, cyclic = false),
        Preset("moon", "🌕 Фазы луны", "Луна сменяет фазы", "", 600),
        Preset("clock", "🕐 Часы", "Стрелка обходит циферблат", "Ждём", 600),
        Preset("heart", "❤️ Сердце", "Сердце переливается цветами", "", 600),
        Preset("custom", "🎞 Свои кадры", "ASCII-арт / флипбук: кадры через строку $FRAME_SEP", "", 800, mono = true),
    )

    fun preset(id: String): Preset = PRESETS.firstOrNull { it.id == id } ?: PRESETS.first()

    // ------------------------------------------------------------------
    // сериализация (строка в поле script правила/кнопки)
    // ------------------------------------------------------------------

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n")
    private fun unesc(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> sb.append('\n')
                    '\\' -> sb.append('\\')
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    fun encode(spec: AnimSpec): String = buildString {
        appendLine("anim=1")
        appendLine("preset=${spec.preset}")
        appendLine("text=${esc(spec.text)}")
        appendLine("interval=${spec.intervalMs}")
        appendLine("loops=${spec.loops}")
        appendLine("count=${spec.count}")
        appendLine("final=${esc(spec.finalText)}")
        appendLine("pack=${spec.packId}")
        appendLine("mono=${if (spec.mono) 1 else 0}")
        appendLine("frames:")
        append(spec.frames.joinToString("\n$FRAME_SEP\n"))
    }

    fun decode(s: String): AnimSpec {
        if (!s.startsWith("anim=")) return AnimSpec()
        val head = s.substringBefore("\nframes:")
        val body = s.substringAfter("\nframes:", "").removePrefix("\n")
        val kv = head.lines().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
        }.toMap()
        return AnimSpec(
            preset = kv["preset"].orEmpty().ifBlank { "spinner" },
            text = unesc(kv["text"].orEmpty()),
            frames = splitFrames(body),
            intervalMs = kv["interval"]?.toIntOrNull() ?: 700,
            loops = kv["loops"]?.toIntOrNull() ?: 1,
            count = kv["count"]?.toIntOrNull() ?: 5,
            finalText = unesc(kv["final"].orEmpty()),
            packId = kv["pack"].orEmpty(),
            mono = kv["mono"] == "1",
        )
    }

    /** Кадры из текстового поля: разделитель — строка «---». */
    fun splitFrames(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var started = false // пустые строки В НАЧАЛЕ кадра значимы (положение в ASCII-арте)
        for (line in text.lines()) {
            if (line.trim() == FRAME_SEP) {
                out.add(cur.toString().trimEnd('\n')); cur.clear(); started = false
            } else {
                if (started) cur.append('\n')
                cur.append(line); started = true
            }
        }
        out.add(cur.toString().trimEnd('\n'))
        return out.filter { it.isNotBlank() }
    }

    fun joinFrames(frames: List<String>): String = frames.joinToString("\n$FRAME_SEP\n")

    // ------------------------------------------------------------------
    // построение кадров
    // ------------------------------------------------------------------

    fun interval(spec: AnimSpec): Long = spec.intervalMs.coerceIn(MIN_INTERVAL, MAX_INTERVAL).toLong()

    private fun sub(s: String, user: String) = s.replace("{user}", user)

    /**
     * Кадры анимации (без итогового текста). Соседние одинаковые кадры
     * убираются (Telegram отвергает правку «message is not modified»),
     * длина — не больше [MAX_FRAMES].
     */
    fun frames(spec: AnimSpec, user: String = "", rnd: Random = Random.Default): List<String> {
        val text = sub(spec.text, user)
        val loops = spec.loops.coerceIn(1, 10)
        fun cycle(symbols: List<String>, make: (String) -> String): List<String> =
            (1..loops).flatMap { symbols.map(make) }

        val raw: List<String> = when (spec.preset) {
            "spinner" -> cycle("⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏".map { it.toString() }) { "$it $text".trim() }
            "progress" -> (0..10).map { i ->
                val bar = "▰".repeat(i) + "▱".repeat(10 - i)
                (if (text.isNotBlank()) "$text\n" else "") + "$bar ${i * 10}%"
            }
            "dots" -> cycle(listOf("", ".", "..", "...")) { "$text$it" }
            "countdown" -> {
                val n = spec.count.coerceIn(1, 30)
                (n downTo 1).map { i -> (if (text.isNotBlank()) "$text\n\n" else "") + "⏳ $i" }
            }
            "typewriter" -> {
                val full = text.ifBlank { sub(spec.finalText, user) }
                if (full.isBlank()) emptyList() else {
                    val step = maxOf(1, (full.length + 19) / 20) // ≤ 20 кадров
                    (step..full.length step step).map { full.take(it) + "▌" } + full
                }
            }
            "matrix" -> {
                val alphabet = "01ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓ"
                (1..6 * loops).map {
                    (1..5).joinToString("\n") {
                        (1..16).map { if (rnd.nextInt(4) == 0) ' ' else alphabet[rnd.nextInt(alphabet.length)] }
                            .joinToString("")
                    } + (if (text.isNotBlank()) "\n$text" else "")
                }
            }
            "slot" -> {
                val reels = listOf("🍒", "🍋", "🔔", "⭐", "💎", "7️⃣")
                (1..8).map { "🎰 | " + (1..3).joinToString(" ") { reels[rnd.nextInt(reels.size)] } + " |" }
            }
            "moon" -> cycle(listOf("🌑", "🌒", "🌓", "🌔", "🌕", "🌖", "🌗", "🌘")) { "$it $text".trim() }
            "clock" -> cycle(listOf("🕐", "🕑", "🕒", "🕓", "🕔", "🕕", "🕖", "🕗", "🕘", "🕙", "🕚", "🕛")) { "$it $text".trim() }
            "heart" -> cycle(listOf("❤️", "🧡", "💛", "💚", "💙", "💜")) { "$it $text".trim() }
            else -> { // custom
                val fr = spec.frames.map { sub(it, user) }.filter { it.isNotBlank() }
                (1..loops).flatMap { fr }
            }
        }
        val out = ArrayList<String>()
        for (f in raw) {
            val fr = f.take(3500)
            if (fr.isNotBlank() && out.lastOrNull() != fr) out.add(fr)
            if (out.size >= MAX_FRAMES) break
        }
        return out
    }

    /** Итоговый текст: фраза из набора (если задан и не пуст) или [AnimSpec.finalText]. */
    fun final(spec: AnimSpec, user: String = "", packItems: List<String> = emptyList(),
              rnd: Random = Random.Default): String {
        val fromPack = if (spec.packId.isNotBlank() && packItems.isNotEmpty())
            packItems[rnd.nextInt(packItems.size)] else ""
        val base = sub(fromPack.ifBlank { spec.finalText }, user)
        return if (spec.preset == "countdown" && base.isBlank()) "🚀" else base
    }

    /** Примерная длительность, сек (для подписи в редакторе). */
    fun durationSec(spec: AnimSpec): Double {
        val n = frames(spec, rnd = Random(1)).size + if (spec.finalText.isNotBlank() || spec.packId.isNotBlank()) 1 else 0
        return (maxOf(0, n - 1) * interval(spec)) / 1000.0
    }

    /** Короткое описание для списков: «🎞 Спиннер · 20 кадров · 0,6 с». */
    fun describe(spec: AnimSpec): String {
        val n = frames(spec, rnd = Random(1)).size
        val p = preset(spec.preset).title
        return "$p · кадров: $n · шаг ${"%.1f".format(interval(spec) / 1000.0)} с"
    }

    /**
     * Telegram обрезает пустые строки в начале и конце сообщения — кадр
     * «съезжает». Пустые строки в начале заполняем невидимым символом ⠀.
     */
    fun keepLayout(frame: String): String {
        if (!frame.startsWith("\n") && !frame.startsWith(" ")) return frame
        return "\u2800" + frame
    }

    /** HTML-экранирование для <pre> (моноширинные кадры). */
    fun html(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Кубики Telegram (sendDice): анимированный эмодзи со случайным значением. */
    val DICE = listOf("🎲", "🎯", "🏀", "⚽", "🎳", "🎰")
}
