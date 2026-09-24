package com.botcontrol.admin.llm

import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.ScriptableObject

/**
 * Local script engine for bot logic («выполнение скриптов по кнопке»).
 * JavaScript (Mozilla Rhino, interpreter mode) in a sandbox:
 *  - no Java access from scripts (safe standard objects + ClassShutter);
 *  - pure interpreter (optimizationLevel=-1) — safe on Android.
 *
 * Script contract:
 *   function handle(e) { ... return "text reply" }
 * where e = { user: String, text: String, chatId: String }.
 */
object BotScript {

    const val EXAMPLE = """function handle(e) {
  var t = e.text.toLowerCase();
  if (t.indexOf("привет") >= 0)
    return "Привет, " + e.user + "!";
  if (t.indexOf("время") >= 0)
    return "Сейчас " + new Date().toLocaleTimeString();
  return "Ты написал: " + e.text;
}"""

    fun run(source: String, user: String, text: String, chatId: Long): Result<String> {
        val cx = Context.enter()
        try {
            cx.optimizationLevel = -1 // interpreter: no bytecode generation on ART
            cx.setClassShutter(ClassShutter { false }) // no java.* from scripts
            val scope = cx.initSafeStandardObjects()
            cx.evaluateString(scope, source, "bot_script", 1, null)
            val fn = scope.get("handle", scope) as? Function
                ?: return Result.failure(IllegalStateException("В скрипте нет функции handle(e)"))
            val arg = cx.newObject(scope)
            ScriptableObject.putProperty(arg, "user", user)
            ScriptableObject.putProperty(arg, "text", text)
            ScriptableObject.putProperty(arg, "chatId", chatId.toString())
            val result = fn.call(cx, scope, scope, arrayOf(arg))
            return Result.success(Context.toString(result))
        } catch (e: Throwable) {
            return Result.failure(
                IllegalStateException("${e.javaClass.simpleName}: ${e.message ?: "ошибка скрипта"}"))
        } finally {
            Context.exit()
        }
    }
}
