package com.botcontrol.admin

import android.app.Application
import androidx.work.Configuration
import com.botcontrol.admin.data.AuthStore
import com.botcontrol.admin.data.BotRepository
import com.botcontrol.admin.data.LocalBotStore
import com.botcontrol.admin.data.local.AppDatabase
import com.botcontrol.admin.data.remote.ApiClient

class BotControlApp : Application(), Configuration.Provider {

    lateinit var authStore: AuthStore
        private set
    lateinit var apiClient: ApiClient
        private set
    lateinit var repository: BotRepository
        private set
    lateinit var database: AppDatabase
        private set
    lateinit var localStore: LocalBotStore
        private set

    override fun onCreate() {
        super.onCreate()
        com.botcontrol.admin.CrashLog.install(this)
        authStore = AuthStore(this)
        apiClient = ApiClient(authStore)
        database = AppDatabase.get(this)
        localStore = LocalBotStore(this)
        repository = BotRepository(apiClient, database.draftDao(), database.botRuleDao())
    }

    // WorkManager on-demand init (also used by StatusWorker via inputData baseUrl)
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()
}
