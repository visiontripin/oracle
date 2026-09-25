import com.botcontrol.admin.data.Anim
import com.botcontrol.admin.data.AnimSpec
import kotlin.random.Random

fun check(name: String, ok: Boolean) { println((if (ok) "ok   " else "FAIL ") + name); if (!ok) failed++ }
var failed = 0

fun main() {
    for (p in Anim.PRESETS) {
        val spec = AnimSpec(preset = p.id, text = p.defaultText.ifBlank { "Привет, {user}" }, loops = 1,
            frames = listOf("  /\\_/\\ \n ( o.o )", "  /\\_/\\ \n ( -.- )"), finalText = "Итог {user}")
        val fr = Anim.frames(spec, "Нео", Random(7))
        println("--- ${p.id}: ${fr.size} кадров; первый='${fr.firstOrNull()?.replace("\n","⏎")?.take(60)}' последний='${fr.lastOrNull()?.replace("\n","⏎")?.take(60)}' итог='${Anim.final(spec, "Нео")}'")
        check("${p.id}: есть кадры", fr.isNotEmpty())
        check("${p.id}: нет соседних дублей", fr.zipWithNext().none { it.first == it.second })
        check("${p.id}: ≤ MAX_FRAMES", fr.size <= Anim.MAX_FRAMES)
        check("${p.id}: {user} подставлен", fr.none { it.contains("{user}") })
    }
    val big = AnimSpec(preset = "spinner", loops = 10, text = "x")
    check("лимит кадров при 10 циклах (${Anim.frames(big).size})", Anim.frames(big).size == Anim.MAX_FRAMES)
    check("интервал снизу 500", Anim.interval(AnimSpec(intervalMs = 100)) == 500L)
    check("интервал сверху 3000", Anim.interval(AnimSpec(intervalMs = 99999)) == 3000L)
    val spec = AnimSpec(preset = "custom", text = "a\nb\\c", frames = listOf("кадр 1\nстрока", "кадр=2", "  /\\_/\\"),
        intervalMs = 800, loops = 3, count = 7, finalText = "итог\nдве строки", packId = "pack_x", mono = true)
    val back = Anim.decode(Anim.encode(spec))
    check("encode→decode без потерь", back == spec)
    if (back != spec) { println(spec); println(back) }
    check("decode мусора → по умолчанию", Anim.decode("какой-то JS") == AnimSpec())
    check("splitFrames", Anim.splitFrames("a\nb\n---\nc\n---\n\n") == listOf("a\nb", "c"))
    val lead = listOf("   🚀\n\n🌍", "\n   🚀\n🌍", "\n\n🌍🔥")
    check("пустые строки в начале кадра сохраняются", Anim.splitFrames(Anim.joinFrames(lead)) == lead)
    check("encode→decode кадров с ведущими переносами",
        Anim.decode(Anim.encode(AnimSpec(preset = "custom", frames = lead))).frames == lead)
    check("keepLayout: ⠀ перед ведущим переносом", Anim.keepLayout("\nx") == "\u2800\nx" && Anim.keepLayout("x") == "x")
    val slot = AnimSpec(preset = "slot", packId = "p")
    check("слот: итог из набора", Anim.final(slot, "", listOf("ДЖЕКПОТ")) == "ДЖЕКПОТ")
    check("отсчёт: итог по умолчанию 🚀", Anim.final(AnimSpec(preset = "countdown")) == "🚀")
    check("html-экранирование", Anim.html("<a&b>") == "&lt;a&amp;b&gt;")
    println(Anim.describe(AnimSpec(preset = "progress")) + " / " + Anim.durationSec(AnimSpec(preset = "progress", finalText = "ok")) + " с")
    println(if (failed == 0) "ALL OK" else "FAILED: $failed")
}
