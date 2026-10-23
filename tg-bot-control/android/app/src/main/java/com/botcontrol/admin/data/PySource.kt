package com.botcontrol.admin.data

/**
 * Мини-сканер Python-подобного текста для импорта конфигов ботов.
 *
 * Код НЕ исполняется. Сканер понимает только структуру: строковые литералы
 * (включая f/r-префиксы и тройные кавычки), комментарии, скобки, логические
 * строки и отступы, функции с декораторами и простые выражения (строки,
 * имена, числа, вызовы, списки/кортежи). Всё остальное — [PyExpr.Other].
 *
 * [masked] — копия текста той же длины, где содержимое строк и комментарии
 * заменены пробелами: по ней безопасно искать скобки, «if», «def» и вызовы,
 * а смещения совпадают с [text].
 */
class PySource(raw: String) {

    val text: String = raw.removePrefix("\uFEFF")
        .replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ")

    private val maskArr = CharArray(text.length)
    val strings: List<StrTok>
    val masked: String
    val lines: List<LLine>
    val functions: List<PyFunc>

    /** Строковый литерал [start, end) — вместе с префиксом и кавычками. */
    data class StrTok(val start: Int, val end: Int, val value: String, val isF: Boolean)

    /** Логическая строка (с учётом скобок и «\» в конце): [start, end), отступ. */
    data class LLine(val start: Int, val end: Int, val indent: Int, val index: Int)

    /** Функция: имя, декораторы (исходный текст), параметры и тело. */
    data class PyFunc(
        val name: String,
        val decorators: List<String>,
        val decoratorRanges: List<IntRange>,
        val header: LLine,
        val params: List<String>,
        /** Логические строки тела (отступ больше, чем у def). */
        val body: List<LLine>,
        /** Тело в одну строку после «:» (def f(): return x) — иначе null. */
        val inlineBody: IntRange?,
    ) {
        val bodyStart: Int get() = inlineBody?.first ?: body.firstOrNull()?.start ?: header.end
        val bodyEnd: Int get() = body.lastOrNull()?.end ?: (inlineBody?.last?.plus(1) ?: header.end)
    }

    init {
        strings = scanStringsAndMask()
        masked = String(maskArr)
        lines = logicalLines()
        functions = findFunctions()
    }

    // ------------------------------------------------------------------
    // строки и комментарии
    // ------------------------------------------------------------------

    private fun isIdent(c: Char) = c.isLetterOrDigit() || c == '_'

    private fun scanStringsAndMask(): List<StrTok> {
        val out = ArrayList<StrTok>()
        val n = text.length
        var i = 0
        while (i < n) {
            val c = text[i]
            if (c == '#') {
                while (i < n && text[i] != '\n') { maskArr[i] = ' '; i++ }
                continue
            }
            // префикс строки: r, u, f, b, rb, br, fr, rf (любой регистр)
            var j = i
            if (c.isLetter() && (i == 0 || !isIdent(text[i - 1]))) {
                var k = i
                while (k < n && k - i < 2 && text[k].lowercaseChar() in "rubf") k++
                if (k > i && k < n && (text[k] == '"' || text[k] == '\'')) j = k
            }
            val q = text[j]
            if ((q == '"' || q == '\'') && (j > i || c == q)) {
                val prefix = text.substring(i, j).lowercase()
                val raw = 'r' in prefix
                val isF = 'f' in prefix
                val triple = j + 2 < n && text[j + 1] == q && text[j + 2] == q
                val qLen = if (triple) 3 else 1
                var k = j + qLen
                val sb = StringBuilder()
                var closed = false
                while (k < n) {
                    val ch = text[k]
                    if (ch == '\\' && k + 1 < n) {
                        if (raw) {
                            sb.append(ch).append(text[k + 1]); k += 2; continue
                        }
                        k = unescapeAt(k, sb); continue
                    }
                    if (!triple && ch == '\n') break // незакрытая строка — обрываем на переводе
                    if (ch == q && (!triple || (k + 2 < n && text[k + 1] == q && text[k + 2] == q))) {
                        closed = true; break
                    }
                    sb.append(ch); k++
                }
                val end = if (closed) k + qLen else k
                // маска: префикс и кавычки остаются, содержимое — пробелы
                for (p in i until end) maskArr[p] = ' '
                for (p in i until j + qLen) if (p < end) maskArr[p] = text[p]
                if (closed) for (p in k until end) maskArr[p] = text[p]
                var value = sb.toString()
                if (isF) value = value.replace("{{", "\u0000L").replace("}}", "\u0000R")
                    .replace("\u0000L", "{").replace("\u0000R", "}")
                out.add(StrTok(i, end, value, isF))
                i = end
                continue
            }
            maskArr[i] = c
            i++
        }
        return out
    }

    /** Разбор escape-последовательности с позиции [k] (там «\»). */
    private fun unescapeAt(k: Int, sb: StringBuilder): Int {
        val n = text.length
        val e = text[k + 1]
        when (e) {
            'n' -> sb.append('\n')
            't' -> sb.append('\t')
            'r' -> sb.append('\r')
            '\\' -> sb.append('\\')
            '\'' -> sb.append('\'')
            '"' -> sb.append('"')
            'a' -> sb.append('\u0007')
            'b' -> sb.append('\b')
            'f' -> sb.append('\u000C')
            'v' -> sb.append('\u000B')
            '0' -> sb.append('\u0000')
            '\n' -> {} // продолжение строки
            'x' -> {
                val hex = text.substring(k + 2, minOf(n, k + 4))
                val v = hex.toIntOrNull(16)
                if (v != null && hex.length == 2) { sb.appendCodePoint(v); return k + 4 }
                sb.append("\\x")
            }
            'u' -> {
                val hex = text.substring(k + 2, minOf(n, k + 6))
                val v = hex.toIntOrNull(16)
                if (v != null && hex.length == 4) { sb.append(v.toChar()); return k + 6 }
                sb.append("\\u")
            }
            'U' -> {
                val hex = text.substring(k + 2, minOf(n, k + 10))
                val v = hex.toIntOrNull(16)
                if (v != null && hex.length == 8) { sb.appendCodePoint(v); return k + 10 }
                sb.append("\\U")
            }
            else -> sb.append('\\').append(e)
        }
        return k + 2
    }

    private fun StringBuilder.appendCodePoint(cp: Int): StringBuilder {
        if (cp in 0..0xFFFF) append(cp.toChar())
        else if (cp in 0x10000..0x10FFFF) {
            val v = cp - 0x10000
            append((0xD800 + (v shr 10)).toChar())
            append((0xDC00 + (v and 0x3FF)).toChar())
        }
        return this
    }

    // ------------------------------------------------------------------
    // логические строки и функции
    // ------------------------------------------------------------------

    private fun logicalLines(): List<LLine> {
        val out = ArrayList<LLine>()
        val n = masked.length
        var depth = 0
        var start = 0
        var i = 0
        fun emit(end: Int) {
            if (end > start && masked.substring(start, end).isNotBlank()) {
                var ind = 0
                while (start + ind < end && text[start + ind] == ' ') ind++
                out.add(LLine(start, end, ind, out.size))
            }
        }
        while (i < n) {
            val c = masked[i]
            when (c) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth > 0) depth--
                '\n' -> {
                    var p = i - 1
                    while (p >= start && masked[p] == ' ') p--
                    val continued = p >= start && masked[p] == '\\'
                    if (depth == 0 && !continued) {
                        emit(i)
                        start = i + 1
                    }
                }
            }
            i++
        }
        emit(n)
        return out
    }

    /** Строки блока после заголовка [header] (отступ больше, до первого «выхода»). */
    fun blockOf(header: LLine): List<LLine> {
        val out = ArrayList<LLine>()
        var k = header.index + 1
        while (k < lines.size && lines[k].indent > header.indent) {
            out.add(lines[k]); k++
        }
        return out
    }

    /** Текст логической строки в маске, без отступа. */
    fun maskedLine(line: LLine): String = masked.substring(line.start, line.end).trim()

    /** Позиция «:» заголовка блока на глубине 0 (или -1). */
    fun headerColon(line: LLine): Int {
        var depth = 0
        for (p in line.start until line.end) {
            when (masked[p]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth > 0) depth--
                ':' -> if (depth == 0) {
                    // «lambda x: …» в заголовке «if»/«def» не встречается в наших конфигах
                    return p
                }
            }
        }
        return -1
    }


    private fun findFunctions(): List<PyFunc> {
        val out = ArrayList<PyFunc>()
        for (line in lines) {
            val m = maskedLine(line)
            val dm = defRe.find(m) ?: continue
            val name = dm.groupValues[1]
            val open = masked.indexOf('(', line.start + line.indent)
            val close = if (open >= 0) matchClose(open) else -1
            val params = if (open >= 0 && close > open) {
                splitTopLevel(open + 1, close).map { r ->
                    text.substring(r.first, r.last + 1).trim()
                        .substringBefore('=').substringBefore(':').trim().removePrefix("*").removePrefix("*")
                }.filter { it.isNotBlank() }
            } else emptyList()
            val colon = if (close > 0) {
                var p = close + 1
                var depth = 0
                var found = -1
                while (p < line.end) {
                    val ch = masked[p]
                    if (ch == '(' || ch == '[' || ch == '{') depth++
                    if (ch == ')' || ch == ']' || ch == '}') depth--
                    if (ch == ':' && depth == 0) { found = p; break }
                    p++
                }
                found
            } else -1
            val inline = if (colon in 0 until line.end - 1 &&
                masked.substring(colon + 1, line.end).isNotBlank()) (colon + 1) until line.end else null
            // декораторы: подряд идущие строки «@…» с тем же отступом
            val decos = ArrayList<String>()
            val decoRanges = ArrayList<IntRange>()
            var k = line.index - 1
            while (k >= 0 && lines[k].indent == line.indent && maskedLine(lines[k]).startsWith("@")) {
                decos.add(0, text.substring(lines[k].start, lines[k].end).trim())
                decoRanges.add(0, lines[k].start until lines[k].end)
                k--
            }
            out.add(PyFunc(name, decos, decoRanges, line, params, blockOf(line), inline))
        }
        return out
    }

    fun function(name: String): PyFunc? = functions.firstOrNull { it.name == name }

    /** Функция, в теле которой находится позиция [offset] (самая вложенная). */
    fun enclosingFunction(offset: Int): PyFunc? =
        functions.filter { offset >= it.header.start && offset < it.bodyEnd }
            .maxByOrNull { it.header.indent }

    // ------------------------------------------------------------------
    // скобки и выражения
    // ------------------------------------------------------------------

    /** Индекс парной закрывающей скобки для открывающей в [open] (или -1). */
    fun matchClose(open: Int): Int {
        val o = masked[open]
        val c = when (o) { '(' -> ')'; '[' -> ']'; '{' -> '}'; else -> return -1 }
        var depth = 0
        for (p in open until masked.length) {
            val ch = masked[p]
            if (ch == '(' || ch == '[' || ch == '{') depth++
            else if (ch == ')' || ch == ']' || ch == '}') {
                depth--
                if (depth == 0) return if (ch == c) p else -1
            }
        }
        return -1
    }

    /** Разбиение [start, end) по запятым глубины 0 (пустой хвост отбрасывается). */
    fun splitTopLevel(start: Int, end: Int): List<IntRange> {
        val out = ArrayList<IntRange>()
        var depth = 0
        var segStart = start
        for (p in start until end) {
            when (masked[p]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) { out.add(segStart until p); segStart = p + 1 }
            }
        }
        if (segStart < end && masked.substring(segStart, end).isNotBlank()) out.add(segStart until end)
        return out.filter { masked.substring(it.first, it.last + 1).isNotBlank() }
    }

    sealed class PyExpr(val start: Int, val end: Int) {
        class Str(s: Int, e: Int, val value: String, val isF: Boolean) : PyExpr(s, e)
        class Name(s: Int, e: Int, val id: String) : PyExpr(s, e)
        class Num(s: Int, e: Int, val value: Double) : PyExpr(s, e)
        class Const(s: Int, e: Int, val value: Boolean?) : PyExpr(s, e)
        class Seq(s: Int, e: Int, val items: List<PyExpr>, val open: Char) : PyExpr(s, e)
        class Call(s: Int, e: Int, val fn: String, val args: List<PyExpr>,
                   val kwargs: Map<String, PyExpr>) : PyExpr(s, e) {
            val shortFn: String get() = fn.substringAfterLast('.')
            fun arg(index: Int, kw: String? = null): PyExpr? =
                (if (kw != null) kwargs[kw] else null) ?: args.getOrNull(index)
        }
        class Other(s: Int, e: Int) : PyExpr(s, e)
    }


    /** Разбор выражения в диапазоне [start, end). */
    fun expr(start: Int, end: Int, depth: Int = 0): PyExpr {
        var s = start
        var e = end
        while (s < e && masked[s].isWhitespace()) s++
        while (e > s && masked[e - 1].isWhitespace()) e--
        if (s >= e || depth > 40) return PyExpr.Other(s, maxOf(s, e))
        // await / return перед выражением
        for (kw in listOf("await ", "return ")) {
            if (masked.startsWith(kw, s)) return expr(s + kw.length, e, depth + 1)
        }
        // склейка строковых литералов: "a" "b" или "a" + "b"
        val toks = strings.filter { it.start >= s && it.end <= e }
        if (toks.isNotEmpty() && toks.first().start == s && toks.last().end == e) {
            var ok = true
            for (t in 0 until toks.size - 1) {
                val gap = masked.substring(toks[t].end, toks[t + 1].start)
                if (gap.isNotBlank() && gap.trim() != "+") { ok = false; break }
            }
            if (ok) return PyExpr.Str(s, e, toks.joinToString("") { it.value }, toks.any { it.isF })
        }
        val c0 = masked[s]
        if ((c0 == '(' || c0 == '[' || c0 == '{') && matchClose(s) == e - 1) {
            val parts = splitTopLevel(s + 1, e - 1)
            val trailingComma = masked.substring(s + 1, e - 1).trimEnd().endsWith(",")
            if (c0 == '(' && parts.size == 1 && !trailingComma) {
                return expr(parts[0].first, parts[0].last + 1, depth + 1)
            }
            return PyExpr.Seq(s, e, parts.map { expr(it.first, it.last + 1, depth + 1) }, c0)
        }
        val m = identChainRe.find(masked, s)
        if (m != null && m.range.first == s) {
            var p = m.range.last + 1
            while (p < e && masked[p] == ' ') p++
            if (p < e && masked[p] == '(' && matchClose(p) == e - 1) {
                val args = ArrayList<PyExpr>()
                val kwargs = LinkedHashMap<String, PyExpr>()
                for (r in splitTopLevel(p + 1, e - 1)) {
                    var a = r.first
                    while (a <= r.last && masked[a].isWhitespace()) a++
                    val seg = masked.substring(a, r.last + 1)
                    if (seg.startsWith("*")) continue
                    val km = kwRe.find(seg)
                    if (km != null) {
                        kwargs[km.groupValues[1]] = expr(a + km.range.last + 1, r.last + 1, depth + 1)
                    } else args.add(expr(a, r.last + 1, depth + 1))
                }
                return PyExpr.Call(s, e, m.value, args, kwargs)
            }
            if (m.range.last + 1 == e) {
                return when (m.value) {
                    "True" -> PyExpr.Const(s, e, true)
                    "False" -> PyExpr.Const(s, e, false)
                    "None" -> PyExpr.Const(s, e, null)
                    else -> PyExpr.Name(s, e, m.value)
                }
            }
        }
        val nm = numRe.find(masked, s)
        if (nm != null && nm.range.first == s && nm.range.last + 1 == e) {
            return PyExpr.Num(s, e, nm.value.toDouble())
        }
        return PyExpr.Other(s, e)
    }


    /** Все вызовы внутри [start, end) — включая вложенные, по порядку. */
    fun calls(start: Int, end: Int): List<PyExpr.Call> {
        val out = ArrayList<PyExpr.Call>()
        val region = masked.substring(start, end)
        for (m in callStartRe.findAll(region)) {
            val nameStart = start + m.range.first
            val name = m.groupValues[1]
            if (name == "def" || name == "if" || name == "elif" || name == "while" ||
                name == "for" || name == "return" || name == "and" || name == "or" ||
                name == "not" || name == "in" || name == "lambda") continue
            // «def name(» — это объявление, не вызов
            val before = masked.substring(maxOf(0, nameStart - 4), nameStart)
            if (before.endsWith("def ")) continue
            val open = start + m.range.last
            val close = matchClose(open)
            if (close < 0 || close >= end) continue
            val ex = expr(nameStart, close + 1)
            if (ex is PyExpr.Call) out.add(ex)
        }
        return out
    }

    fun src(e: PyExpr): String = text.substring(e.start, e.end)
    fun src(r: IntRange): String = text.substring(r.first, r.last + 1)
    fun maskedOf(e: PyExpr): String = masked.substring(e.start, e.end)

    /** Комментарий «# …» на физической строке, где находится [offset]. */
    fun trailingComment(offset: Int): String {
        val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        for (p in lineStart until lineEnd) {
            if (text[p] == '#' && masked[p] == ' ' && strings.none { p >= it.start && p < it.end }) {
                return text.substring(p + 1, lineEnd).trim()
            }
        }
        return ""
    }

    /** Присваивание «NAME = выражение» в логической строке (или null). */
    fun assignment(line: LLine): Pair<String, PyExpr>? {
        val m = assignRe.find(masked, line.start + line.indent) ?: return null
        if (m.range.first != line.start + line.indent || m.range.last >= line.end) return null
        return m.groupValues[1] to expr(m.range.last + 1, line.end)
    }


    /** Присваивания на уровне модуля (отступ 0): имя → выражение (последнее побеждает). */
    fun moduleAssignments(): Map<String, PyExpr> {
        val out = LinkedHashMap<String, PyExpr>()
        for (line in lines) {
            if (line.indent != 0) continue
            val a = assignment(line) ?: continue
            out[a.first] = a.second
        }
        return out
    }

    private companion object {
        private val defRe = Regex("""^(?:async\s+)?def\s+([A-Za-z_]\w*)\s*\(""")
        private val identChainRe = Regex("""[A-Za-z_][\w.]*""")
        private val numRe = Regex("""-?\d+(?:\.\d+)?""")
        private val kwRe = Regex("""^([A-Za-z_]\w*)\s*=(?!=)""")
        private val callStartRe = Regex("""(?<![\w.])([A-Za-z_][\w.]*)\s*\(""")
        private val assignRe = Regex("""([A-Za-z_]\w*)[ \t]*(?::[ \t]*[\w\[\], .|]+)?[ \t]*=(?!=)""")
    }
}
