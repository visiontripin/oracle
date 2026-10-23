import com.botcontrol.admin.data.*
import com.botcontrol.admin.data.local.BotRuleEntity
import java.io.File
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Круг «код → импорт → хранилище → экспорт → импорт».
 * usage: RoundTripKt file.py [out.py] [--ai]
 * Применение — упрощённая копия ImportScreen.applyParsed (наборы imp_X →
 * pack_x, в том числе внутри анимаций). Печатает экспорт и сравнивает
 * сводки первого и второго импорта: правила, кнопки, расписание, наборы.
 */
fun <T> runSync(block: suspend () -> T): T {
    var r: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { r = it })
    return r!!.getOrThrow()
}

fun summary(p: ScriptImporter.Parsed, packName: (String) -> String): List<String> {
    val out = ArrayList<String>()
    fun anim(script: String): String {
        val s = Anim.decode(script)
        return "anim[${s.preset} f=${s.frames.size} ${s.intervalMs}ms loops=${s.loops} final='${s.finalText.take(30)}' " +
            "text='${s.text.take(20)}' pack=${if (s.packId.isBlank()) "" else packName(s.packId)}]"
    }
    fun btn(b: InlineBtn): String = "«${b.label}» id=${b.id} ${b.action}" +
        (if (b.action == "pack") " pack=${packName(b.packId)}" else "") +
        (if (b.action == "anim") " " + anim(b.script) else "") +
        (if (b.action == "text" || b.action == "dice" || b.action.startsWith("reminders")) " text='${b.text.replace("\n", "⏎").take(40)}'" else "") +
        (if (b.toast.isNotBlank()) " toast='${b.toast}'" else "") +
        (if (b.edit) " EDIT" else "") + (if (b.url.isNotBlank()) " url" else "")
    p.rules.sortedBy { it.type + it.pattern }.forEach { r ->
        out.add("rule ${r.type} '${r.pattern}' ${r.actionType}" +
            (if (r.actionType == "pack") " pack=${packName(r.packId)}" else "") +
            (if (r.actionType == "anim") " " + anim(r.script) else "") +
            (if (r.actionType == "text" || r.actionType == "dice") " '${r.text.replace("\n", "⏎").take(60)}'" else ""))
        r.menu.forEach { out.add("    " + btn(it)) }
    }
    p.schedule.sortedBy { it.hour * 60 + it.minute + it.days.size * 10000 }.forEach { e ->
        out.add("event ${e.timeLabel()} ${e.days} '${e.text.take(40)}' menu=${e.menu.map { it.id }}")
    }
    val used = p.rules.map { it.packId } + p.allButtons.map { it.packId }
    p.packs.sortedBy { it.name }.forEach { out.add("pack ${packName(it.id)} ${it.items.size}") }
    out.add("clarify ${p.clarify.size}; keyboard ${p.keyboardRows.flatten()}")
    return out
}

fun main(args: Array<String>) {
    val file = args[0]
    val outFile = args.getOrNull(1)?.takeIf { !it.startsWith("--") }
    val first = ScriptImporter.parse(File(file).readText())

    val store = LocalBotStore()
    val repo = BotRepository()
    store.llmOn = "--ai" in args
    store.prompt = first.systemPrompt.orEmpty()
    val ids = first.packs.associate { it.id to "pack_" + it.id.removePrefix("imp_").lowercase() }
    fun pid(raw: String) = ids[raw] ?: raw.removePrefix("imp_")
    fun animFix(script: String): String {
        val s = Anim.decode(script)
        return if (s.packId.startsWith("imp_")) Anim.encode(s.copy(packId = pid(s.packId))) else script
    }
    fun fix(m: List<InlineBtn>) = m.map { b ->
        val x = if (b.packId.startsWith("imp_")) b.copy(packId = pid(b.packId)) else b
        if (x.action == "anim") x.copy(script = animFix(x.script)) else x
    }.withIds()
    store.packList = first.packs.map { it.copy(id = ids.getValue(it.id)) }
    store.clarifyList = first.clarify
    store.keyboardList = first.keyboardRows.flatten()
    first.cooldownSec?.let { store.cooldown = it }
    first.typingSec?.let { store.typing = it }
    store.events = first.schedule.map { it.copy(menu = fix(it.menu)) }
    repo.rules = first.rules.mapIndexed { i, r ->
        BotRuleEntity(id = i + 1, type = r.type, pattern = r.pattern, actionType = r.actionType,
            responseText = when (r.actionType) { "text" -> r.text; "dice" -> r.text.ifBlank { "🎲" }; else -> "" },
            packId = if (r.actionType == "pack") pid(r.packId) else "",
            script = if (r.actionType == "anim") animFix(r.script) else r.script,
            menu = BotJson.save(fix(r.menu)), botId = 2L)
    }

    val export = runSync { SettingsExporter.build(store, repo, 2L) }
    if (outFile != null) File(outFile).writeText(export) else println(export)

    val second = ScriptImporter.parse(export)
    val names1 = first.packs.associate { it.id to it.name }
    val names2 = second.packs.associate { it.id to it.name }
    // имена наборов сравниваем по названию (id при экспорте меняется)
    val s1 = summary(first) { names1[it] ?: store.packList.firstOrNull { p -> p.id == it }?.name ?: it }
    val s2 = summary(second) { names2[it] ?: it }
    // id кнопок: в первом импорте могут быть пустыми (withIds их придумает)
    val norm = { l: List<String> -> l.map { it.replace(Regex("id=\\S*"), "id=_").replace(Regex("menu=\\[.*]"), "menu=") } }
    val a = norm(s1); val b = norm(s2)
    System.err.println("=== первый импорт (${a.size}) / второй (${b.size})")
    var diff = 0
    val onlyA = a.filter { it !in b }; val onlyB = b.filter { it !in a }
    onlyA.forEach { System.err.println("  - $it"); diff++ }
    onlyB.forEach { System.err.println("  + $it"); diff++ }
    System.err.println("warnings2: " + second.warnings.joinToString(" | "))
    System.err.println(if (diff == 0) "ROUNDTRIP OK" else "ROUNDTRIP DIFF: $diff")
}
