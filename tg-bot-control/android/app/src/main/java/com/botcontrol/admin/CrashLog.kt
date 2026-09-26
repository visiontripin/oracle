package com.botcontrol.admin

import android.content.Context
import android.util.Log
import java.io.File

/** Ловим падения и показываем их на главном экране — вместо немой аварии. */
object CrashLog {
    private fun file(context: Context) = File(context.applicationContext.filesDir, "crash.log")

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                file(context).writeText(
                    "Поток: ${thread.name}\n" +
                        "${error.javaClass.simpleName}: ${error.message}\n" +
                        (error.stackTrace.take(8).joinToString("\n")),
                )
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun last(context: Context): String = try {
        val f = file(context)
        if (f.exists()) f.readText().lineSequence().take(10).joinToString("\n") else ""
    } catch (_: Throwable) {
        ""
    }

    fun clear(context: Context) {
        try {
            file(context).delete()
        } catch (_: Throwable) {
        }
    }
}
