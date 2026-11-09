package com.botcontrol.admin.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.botcontrol.admin.BotControlApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Автозапуск ботов после перезагрузки телефона (исправление A2).
 *
 * Если пользователь не выключил «Автозапуск после перезагрузки» и есть
 * включённые боты — поднимаем LocalBotService: его цикл без действия
 * запускает все включённые профили (см. onStartCommand).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val store = (context.applicationContext as BotControlApp).localStore
                val wanted = store.autostartOnBoot() && store.profiles().any { it.enabled }
                if (wanted) {
                    androidx.core.content.ContextCompat.startForegroundService(
                        context,
                        Intent(context, LocalBotService::class.java),
                    )
                }
            } catch (t: Throwable) {
                Log.w("BotControl", "Автозапуск после перезагрузки не удался: ${t.message}")
            } finally {
                pending.finish()
            }
        }
    }
}
