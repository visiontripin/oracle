package com.botcontrol.admin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.botcontrol.admin.ui.navigation.NavGraph
import com.botcontrol.admin.ui.theme.BotControlTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Тёмная схема: иконки статус-бара и навигации — СВЕТЛЫЕ,
        // подложки прозрачные (иначе системный текст выглядит тёмным).
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(
                android.graphics.Color.TRANSPARENT,
            ),
        )
        super.onCreate(savedInstanceState)
        val app = application as BotControlApp
        setContent {
            BotControlTheme {
                NavGraph(
                    authStore = app.authStore,
                    repository = app.repository,
                    apiClient = app.apiClient,
                    localStore = app.localStore,
                )
            }
        }
    }
}
