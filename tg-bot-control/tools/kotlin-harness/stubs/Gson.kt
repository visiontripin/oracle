package com.google.gson

/**
 * Заглушка Gson для харнесса: toJson кладёт объект в память и отдаёт ключ,
 * fromJson достаёт его обратно. Этого хватает для круга
 * «импорт → хранилище → экспорт» внутри одного процесса (меню кнопок в
 * правилах хранятся строкой BotJson.save(...)). Чужие строки → null.
 */
object GsonRegistry {
    val objects = HashMap<String, Any?>()
    var next = 0
}

class Gson {
    @Suppress("UNCHECKED_CAST")
    fun <T> fromJson(s: String, t: java.lang.reflect.Type): T? = GsonRegistry.objects[s] as T?
    fun toJson(a: Any?): String {
        val key = "#obj${GsonRegistry.next++}"
        GsonRegistry.objects[key] = a
        return key
    }
}
