package com.botcontrol.admin.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.botcontrol.admin.BotControlApp
import com.botcontrol.admin.R
import com.botcontrol.admin.llm.DeviceLlm
import com.botcontrol.admin.llm.DevicePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service (dataSync) that bridges the server job queue to the
 * on-device model: claims jobs -> generates LOCALLY on this phone -> posts
 * the reply back. Started/stopped from the Local LLM (On-device) screen.
 *
 * Android 14+ compliance: foregroundServiceType="dataSync" is declared in
 * the manifest and passed to startForeground; the service is only started
 * from the foreground UI.
 */
class LlmAgentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        isRunning = true
        scope.launch { runAgentLoop() }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runAgentLoop() {
        val app = application as BotControlApp
        val repository = app.repository
        val prefs = DevicePrefs(this)
        while (scope.isActive) {
            try {
                val claim = repository.llmClaimJob()
                val job = claim.getOrNull()?.job
                if (job == null) {
                    notifyContent("Агент активен — заданий нет")
                    delay(POLL_IDLE_MS)
                    continue
                }
                notifyContent("Генерация для чата ${job.chatId} (задание #${job.id})…")

                if (prefs.selectedModel.isBlank()) {
                    repository.llmJobFail(job.id, "no model selected on device")
                    continue
                }
                val replyResult = DeviceLlm.ensureLoaded(this, prefs.selectedModel, prefs.toParams())
                    .mapCatching { DeviceLlm.generate(job.systemPrompt, job.message).getOrThrow() }
                replyResult.fold(
                    onSuccess = { reply -> repository.llmJobResult(job.id, reply) },
                    onFailure = { e -> repository.llmJobFail(job.id, e.message ?: "generation error") },
                )
                notifyContent("Агент активен — задание #${job.id} выполнено")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notifyContent("Ошибка агента: ${e.message?.take(80)}")
                delay(POLL_ERROR_MS)
            }
        }
    }

    // ---------- notification ----------
    private fun startInForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "LLM agent", NotificationManager.IMPORTANCE_LOW)
            )
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification("Агент активен"),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun notifyContent(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val CHANNEL_ID = "llm_agent"
        private const val NOTIFICATION_ID = 1001
        private const val POLL_IDLE_MS = 5_000L
        private const val POLL_ERROR_MS = 15_000L

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(
                context, Intent(context, LlmAgentService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LlmAgentService::class.java))
            DeviceLlm.unload()
        }
    }
}
