import com.botcontrol.admin.data.*
import com.botcontrol.admin.data.local.BotRuleEntity
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Изоляция ботов в экспорте/импорте наборов (v1.6.3).
 * usage: ./run.sh out IsolationKt   → «ISOLATION OK» или список ошибок.
 * Бот 1 и бот 2 делят общую библиотеку наборов; экспорт бота 1 не должен
 * тащить наборы бота 2 и общие наборы, которыми пользуется бот 2.
 */
private fun <T> sync(block: suspend () -> T): T {
    var r: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { r = it })
    return r!!.getOrThrow()
}

fun main() {
    val errors = ArrayList<String>()
    fun check(ok: Boolean, what: String) { if (!ok) errors.add(what) }

    val slot = Anim.encode(AnimSpec(preset = "slot", packId = "pack_slot1"))
    val rules1 = listOf(
        BotRuleEntity(id = 1, type = "command", pattern = "/a", actionType = "pack", packId = "pack_a", botId = 1),
        BotRuleEntity(id = 2, type = "command", pattern = "/slot", actionType = "anim", script = slot, botId = 1),
    )
    val rules2 = listOf(
        BotRuleEntity(id = 3, type = "command", pattern = "/b", actionType = "pack", packId = "pack_b", botId = 2),
        BotRuleEntity(id = 4, type = "command", pattern = "/u", actionType = "text", botId = 2,
            menu = BotJson.save(listOf(InlineBtn(id = "x", label = "X", action = "pack", packId = "pack_used2")))),
    )
    val store = LocalBotStore().apply {
        profileList = listOf(BotProfile(1, "one"), BotProfile(2, "two"))
        eventsByBot = mapOf(1L to emptyList(), 2L to emptyList())
        packList = listOf(
            ReplyPack("pack_a", "A", listOf("a1"), ownerBotId = 1),
            ReplyPack("pack_slot1", "Slot", listOf("s1"), ownerBotId = 1),
            ReplyPack("pack_b", "B", listOf("b1"), ownerBotId = 2),
            ReplyPack("pack_own2", "Own2", listOf("o2"), ownerBotId = 2),
            ReplyPack("pack_used2", "Used2", listOf("u2")),   // общий, но им пользуется бот 2
            ReplyPack("pack_free", "Free", listOf("f1")),     // общий, никем не используется
        )
    }
    val repo = BotRepository().apply { rulesByBot = mapOf(1L to rules1, 2L to rules2) }

    val refs1 = SettingsExporter.packRefs(rules1, emptyList())
    check(refs1 == setOf("pack_a", "pack_slot1"), "packRefs(бот 1) = $refs1")
    val refs2 = SettingsExporter.packRefs(rules2, emptyList())
    check(refs2 == setOf("pack_b", "pack_used2"), "packRefs(бот 2) = $refs2 (кнопка с набором)")

    val out1 = sync { SettingsExporter.build(store, repo, 1L) }
    for (c in listOf("A", "SLOT", "FREE")) check(Regex("^$c = \\[", RegexOption.MULTILINE).containsMatchIn(out1), "экспорт бота 1 без $c")
    for (c in listOf("B", "OWN2", "USED2")) check(!Regex("^(PACK_)?$c = \\[", RegexOption.MULTILINE).containsMatchIn(out1), "экспорт бота 1 утёк набор $c")
    check("\"b1\"" !in out1 && "\"o2\"" !in out1 && "\"u2\"" !in out1, "в экспорте бота 1 тексты бота 2")

    val out2 = sync { SettingsExporter.build(store, repo, 2L) }
    check("\"a1\"" !in out2 && "\"s1\"" !in out2, "в экспорте бота 2 тексты бота 1")
    check("\"b1\"" in out2 && "\"o2\"" in out2 && "\"u2\"" in out2, "экспорт бота 2 потерял свои наборы")

    // Метка бота в журнале (копия BotLog.tagged подкладывается run-скриптом)
    val t = "[@one]"
    check(tagged(t, "📩 Сообщение: 'x'") == "📩 [@one] Сообщение: 'x'", tagged(t, "📩 Сообщение: 'x'"))
    check(tagged(t, "   • Проверяю правила (3)") == "   • [@one] Проверяю правила (3)", tagged(t, "   • Проверяю правила (3)"))
    check(tagged(t, "Текст") == "[@one] Текст", tagged(t, "Текст"))
    check(tagged(t, "🎞 [@one] уже") == "🎞 [@one] уже", "двойная метка")

    if (errors.isEmpty()) println("ISOLATION OK") else { errors.forEach { println("✗ $it") }; kotlin.system.exitProcess(1) }
}
