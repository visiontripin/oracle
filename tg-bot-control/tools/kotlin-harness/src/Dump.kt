import com.botcontrol.admin.data.*
import java.io.File

fun dumpBtn(b: InlineBtn): String = buildString {
    append("[r${b.row}] «${b.label}» id=${b.id} ${b.action}")
    if (b.toast.isNotBlank()) append(" toast='${b.toast}'")
    if (b.toastNoChange.isNotBlank()) append(" toastSame='${b.toastNoChange}'")
    if (b.packId.isNotBlank()) append(" pack=${b.packId}")
    if (b.text.isNotBlank()) append(" text='${b.text.replace("\n", "⏎").take(70)}'")
    if (b.url.isNotBlank()) append(" url=${b.url}")
    if (b.edit) append(" EDIT")
    if (b.script.isNotBlank()) append(" script=${b.script.length}ch")
}

fun main(args: Array<String>) {
    for (path in args) {
        val p = ScriptImporter.parse(File(path).readText())
        println("=================== $path")
        println("prompt: ${p.systemPrompt?.take(60)?.replace("\n", "⏎")} (${p.systemPrompt?.length})")
        println("temp=${p.temperature} maxTok=${p.maxTokens} topK=${p.topK} hist=${p.historyLimit} cd=${p.cooldownSec} typing=${p.typingSec}")
        println("clarify: ${p.clarify.size} ${p.clarify.take(3)}")
        println("packs: " + p.packs.joinToString { "${it.id}='${it.name}'(${it.items.size})" })
        println("menuCommands: " + p.menuCommands.joinToString { "/${it.command}=${it.description}" })
        println("keyboard: ${p.keyboardRows}")
        println("channel=${p.channel} listings=${p.listingsOn}")
        println("rules:")
        p.rules.forEach { r ->
            println("  ${r.type} '${r.pattern}' -> ${r.actionType} ${if (r.packId.isNotBlank()) r.packId else ""} text='${r.text.replace("\n", "⏎").take(80)}'")
            r.menu.forEach { println("      " + dumpBtn(it)) }
        }
        println("schedule (${p.schedule.size}):")
        p.schedule.sortedBy { it.hour * 60 + it.minute }.forEach { e ->
            println("  ${e.timeLabel()} ${e.daysLabel()} ch=${e.toChannel} '${e.text.take(50)}' menu=${e.menu.size}")
        }
        val menus = p.schedule.map { it.menu }.filter { it.isNotEmpty() }.distinct()
        menus.forEach { m -> println("  event menu:"); m.forEach { println("      " + dumpBtn(it)) } }
        println("warnings:"); p.warnings.forEach { println("  ⚠️ $it") }
        println("sections:"); p.sections().forEach { s -> println("  [${s.key}] ${s.title}: ${s.details.joinToString(" | ").take(200)}") }
    }
}
