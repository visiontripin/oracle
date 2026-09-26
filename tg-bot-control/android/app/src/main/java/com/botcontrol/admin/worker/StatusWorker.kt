package com.botcontrol.admin.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.botcontrol.admin.data.AuthStore
import com.botcontrol.admin.data.remote.ApiClient
import java.util.concurrent.TimeUnit

/**
 * Periodic background poll of GET /status (WorkManager).
 * Stores last known online state; UI reads it on cold start.
 * No shell/exec involved — pure HTTPS.
 */
class StatusWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val baseUrl = inputData.getString(KEY_BASE_URL).orEmpty()
        if (baseUrl.isBlank()) return Result.success()
        return try {
            val authStore = AuthStore(applicationContext)
            val api = ApiClient(authStore).service(baseUrl)
            val resp = api.status()
            if (resp.isSuccessful) {
                val out = workDataOf(
                    KEY_ONLINE to (resp.body()?.online == true),
                    KEY_VERSION to (resp.body()?.version.orEmpty()),
                )
                Result.success(out)
            } else {
                Result.retry()
            }
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val NAME = "status-poll"
        const val KEY_BASE_URL = "base_url"
        const val KEY_ONLINE = "online"
        const val KEY_VERSION = "version"

        fun schedule(context: Context, baseUrl: String) {
            val req = PeriodicWorkRequestBuilder<StatusWorker>(15, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_BASE_URL to baseUrl))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
