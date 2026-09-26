import com.botcontrol.admin.data.PySource
fun main() {
    val q3 = "\"\"\""
    val code = """
# comment "not a string"
X = ( "a"  # c1
      "b\n" )
Y = f"Привет, {name}! {{x}}"
def kb(a, b=1):
    m = InlineKeyboardMarkup()
    m.row(InlineKeyboardButton("▶️ Запуск", callback_data="start"),
          InlineKeyboardButton('⏹ Стоп', callback_data='stop'))
    return m
@bot.message_handler(commands=["start"])
def cmd_start(msg):
    bot.send_message(msg.chat.id, "Hi #1", reply_markup=kb())
T = r'raw\n' ${q3}triple
line${q3}
""".replace("\\n", "\\n")
    val s = PySource(code)
    println("strings:"); s.strings.forEach { println("  [${it.start},${it.end}) f=${it.isF} '${it.value}'") }
    println("lines:"); s.lines.forEach { println("  ${it.index} ind=${it.indent} '${s.masked.substring(it.start, it.end)}'") }
    println("funcs:"); s.functions.forEach { f -> println("  ${f.name}(${f.params}) decos=${f.decorators} body=${f.body.size}") }
    val mods = s.moduleAssignments()
    mods.forEach { (k, v) -> println("  $k = ${v::class.simpleName} ${ (v as? PySource.PyExpr.Str)?.value }") }
    val f = s.function("cmd_start")!!
    s.calls(f.bodyStart, f.bodyEnd).forEach { c -> println("call ${c.fn} args=${c.args.map { it::class.simpleName }} kw=${c.kwargs.keys}") }
    val k = s.function("kb")!!
    s.calls(k.bodyStart, k.bodyEnd).forEach { c -> println("call ${c.fn} args=${c.args.map { (it as? PySource.PyExpr.Call)?.fn ?: it::class.simpleName }} kw=${c.kwargs.map { it.key + "=" + ((it.value as? PySource.PyExpr.Str)?.value) }}") }
}
