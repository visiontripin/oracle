package com.botcontrol.admin.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.botcontrol.admin.data.AuthStore
import com.botcontrol.admin.data.BotRepository
import com.botcontrol.admin.data.LocalBotStore
import com.botcontrol.admin.data.remote.ApiClient
import com.botcontrol.admin.ui.components.LoadingBox
import com.botcontrol.admin.ui.screens.AiSettingsScreen
import com.botcontrol.admin.ui.screens.AuditScreen
import com.botcontrol.admin.ui.screens.BackupScreen
import com.botcontrol.admin.ui.screens.BehaviorScreen
import com.botcontrol.admin.ui.screens.BotSettingsScreen
import com.botcontrol.admin.ui.screens.BotTreeScreen
import com.botcontrol.admin.ui.screens.DashboardScreen
import com.botcontrol.admin.ui.screens.FaqScreen
import com.botcontrol.admin.ui.screens.ButtonsScreen
import com.botcontrol.admin.ui.screens.ImportScreen
import com.botcontrol.admin.ui.screens.PackEditScreen
import com.botcontrol.admin.ui.screens.LocalBotScreen
import com.botcontrol.admin.ui.screens.LocalSetupScreen
import com.botcontrol.admin.ui.screens.LoginScreen
import com.botcontrol.admin.ui.screens.LogsScreen
import com.botcontrol.admin.ui.screens.LlmChatScreen
import com.botcontrol.admin.ui.screens.AppLogScreen
import com.botcontrol.admin.ui.screens.ExportScreen
import com.botcontrol.admin.ui.screens.FileServerScreen
import com.botcontrol.admin.ui.screens.LogScreen
import com.botcontrol.admin.ui.screens.LlmScreen
import com.botcontrol.admin.ui.screens.OnDeviceScreen
import com.botcontrol.admin.ui.screens.PluginDetailScreen
import com.botcontrol.admin.ui.screens.PluginEditorScreen
import com.botcontrol.admin.ui.screens.PluginsScreen
import com.botcontrol.admin.ui.screens.RemindersScreen
import com.botcontrol.admin.ui.screens.PacksScreen
import com.botcontrol.admin.ui.screens.ScriptEditorScreen
import com.botcontrol.admin.ui.screens.SettingsScreen
import com.botcontrol.admin.ui.screens.SimulateScreen
import com.botcontrol.admin.ui.screens.WelcomeScreen
import kotlinx.coroutines.launch

object Routes {
    const val WELCOME = "welcome"
    const val LOCAL_SETUP = "localsetup"
    const val LOCAL_BOT = "localbot"
    const val TREE = "tree/{id}"
    const val LOGIN = "login"
    const val DASHBOARD = "dashboard"
    const val PLUGINS = "plugins"
    const val PLUGIN_DETAIL = "plugin/{id}"
    const val EDITOR = "editor/{id}"
    const val LLM = "llm"
    const val LLM_DEVICE = "llmdevice"
    const val LLM_CHAT = "llmchat"
    const val REMINDERS = "reminders"
    const val PACKS = "packs"
    const val AI_SETTINGS = "aisettings"
    const val BOT_SETTINGS = "botsettings"
    const val IMPORT = "import"
    const val BUTTONS = "buttons"
    const val BEHAVIOR = "behavior"
    const val DEV_LOG = "devlog"
    const val EXPORT = "export"
    const val FILES = "files"
    const val APP_LOG = "applog"
    const val PACK_EDIT = "packedit/{id}"
    const val SIMULATE = "simulate"
    const val SCRIPT = "script/{id}"
    const val FAQ = "faq"
    const val LOGS = "logs"
    const val AUDIT = "audit"
    const val SETTINGS = "settings"
    const val BACKUP = "backup"

    fun tree(id: Long) = "tree/$id"
    fun pluginDetail(id: String) = "plugin/$id"
    fun editor(id: String) = "editor/$id"
}

@Composable
fun NavGraph(
    authStore: AuthStore,
    repository: BotRepository,
    apiClient: ApiClient,
    localStore: LocalBotStore,
) {
    val nav = rememberNavController()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var startRoute by remember { mutableStateOf<String?>(null) }
    var isLocalMode by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val mode = localStore.mode()
        isLocalMode = mode != "server"
        startRoute = when {
            mode == "server" && !authStore.token().isNullOrBlank() &&
                authStore.baseUrl().isNotBlank() -> {
                repository.attach(authStore, authStore.baseUrl())
                Routes.DASHBOARD
            }
            mode == "server" -> Routes.LOGIN
            else -> Routes.WELCOME // главный экран: боты со статусом, «+», FAQ
        }
    }

    val start = startRoute ?: return LoadingBox()

    // Контент не залезает под статус-бар (edge-to-edge сохранён).
    Box(Modifier.fillMaxSize().statusBarsPadding()) {
    NavHost(navController = nav, startDestination = start) {
        composable(Routes.WELCOME) {
            WelcomeScreen(
                localStore = localStore,
                onAddBot = { nav.navigate(Routes.LOCAL_SETUP) },
                onServer = { nav.navigate(Routes.LOGIN) },
                onOpenBot = { id -> nav.navigate(Routes.tree(id)) },
                onFaq = { nav.navigate(Routes.FAQ) },
            )
        }
        composable(Routes.LOCAL_SETUP) {
            LocalSetupScreen(
                localStore = localStore,
                onDone = {
                    nav.navigate(Routes.WELCOME) { popUpTo(0) }
                },
                onCancel = { nav.popBackStack() },
            )
        }
        composable(Routes.TREE) { entry ->
            val id = entry.arguments?.getString("id")?.toLongOrNull() ?: 1L
            BotTreeScreen(
                botId = id,
                localStore = localStore,
                onOpen = { route -> nav.navigate(route) },
                onAddBot = { nav.navigate(Routes.LOCAL_SETUP) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.LOCAL_BOT) {
            LocalBotScreen(
                repository = repository,
                localStore = localStore,
                onManageModels = { nav.navigate(Routes.LLM_DEVICE) },
                onLlmChat = { nav.navigate(Routes.LLM_CHAT) },
                onOpenScript = { id -> nav.navigate("script/$id") },
                onReminders = { nav.navigate(Routes.REMINDERS) },
                onPacks = { nav.navigate(Routes.PACKS) },
                onAiSettings = { nav.navigate(Routes.AI_SETTINGS) },
                onBotSettings = { nav.navigate(Routes.BOT_SETTINGS) },
                onSimulate = { nav.navigate(Routes.SIMULATE) },
                onChangeMode = { nav.navigate(Routes.WELCOME) { popUpTo(0) } },
                onEditToken = { nav.navigate(Routes.LOCAL_SETUP) },
            )
        }
        composable(Routes.LOGIN) {
            LoginScreen(
                authStore = authStore,
                repository = repository,
                localStore = localStore,
                onLoggedIn = { nav.navigate(Routes.DASHBOARD) { popUpTo(0) } },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.DASHBOARD) {
            DashboardScreen(
                repository = repository,
                authStore = authStore,
                onPlugins = { nav.navigate(Routes.PLUGINS) },
                onLlm = { nav.navigate(Routes.LLM) },
                onLogs = { nav.navigate(Routes.LOGS) },
                onAudit = { nav.navigate(Routes.AUDIT) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onBackup = { nav.navigate(Routes.BACKUP) },
                onLoggedOut = {
                    scope.launch {
                        localStore.setMode("")
                        authStore.clearSession()
                        nav.navigate(Routes.WELCOME) { popUpTo(0) }
                    }
                },
            )
        }
        composable(Routes.PLUGINS) {
            PluginsScreen(
                repository,
                onOpen = { id -> nav.navigate(Routes.pluginDetail(id)) },
            )
        }
        composable(
            Routes.PLUGIN_DETAIL,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) {
            val id = it.arguments?.getString("id").orEmpty()
            PluginDetailScreen(
                repository = repository,
                pluginId = id,
                onEdit = { nav.navigate(Routes.editor(id)) },
            )
        }
        composable(
            Routes.EDITOR,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) {
            PluginEditorScreen(
                repository = repository,
                pluginId = it.arguments?.getString("id").orEmpty(),
                onSaved = { nav.popBackStack() },
            )
        }
        composable(Routes.LLM) { LlmScreen(repository, onDevice = { nav.navigate(Routes.LLM_DEVICE) }) }
        composable(Routes.LLM_DEVICE) {
            OnDeviceScreen(repository, onBack = { nav.popBackStack() }, localMode = isLocalMode, localStore = localStore)
        }
        composable(Routes.LLM_CHAT) {
            LlmChatScreen(localStore, onBack = { nav.popBackStack() })
        }
        composable(Routes.REMINDERS) {
            RemindersScreen(localStore, onBack = { nav.popBackStack() })
        }
        composable(Routes.PACKS) {
            PacksScreen(localStore, onBack = { nav.popBackStack() },
                onEditPack = { id -> nav.navigate("packedit/$id") })
        }
        composable(
            Routes.PACK_EDIT,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) {
            PackEditScreen(
                packId = it.arguments?.getString("id").orEmpty(),
                localStore = localStore,
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.BUTTONS) {
            ButtonsScreen(localStore, onBack = { nav.popBackStack() })
        }
        composable(Routes.BEHAVIOR) {
            BehaviorScreen(localStore, onBack = { nav.popBackStack() })
        }
        composable(Routes.DEV_LOG) {
            LogScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.EXPORT) {
            ExportScreen(localStore, repository, onBack = { nav.popBackStack() })
        }
        composable(Routes.APP_LOG) {
            AppLogScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.FILES) {
            FileServerScreen(localStore, onBack = { nav.popBackStack() })
        }
        composable(Routes.AI_SETTINGS) {
            AiSettingsScreen(localStore, onBack = { nav.popBackStack() })
        }
        composable(Routes.BOT_SETTINGS) {
            BotSettingsScreen(localStore, repository, onBack = { nav.popBackStack() })
        }
        composable(Routes.SIMULATE) {
            SimulateScreen(repository, localStore, onBack = { nav.popBackStack() })
        }
        composable(Routes.FAQ) {
            FaqScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.IMPORT) {
            ImportScreen(localStore, repository, onBack = { nav.popBackStack() })
        }
        composable(
            Routes.SCRIPT,
            arguments = listOf(navArgument("id") { type = NavType.IntType }),
        ) {
            val id = it.arguments?.getInt("id") ?: return@composable
            ScriptEditorScreen(repository, id, onBack = { nav.popBackStack() })
        }
        composable(Routes.LOGS) { LogsScreen(repository, apiClient, authStore) }
        composable(Routes.AUDIT) { AuditScreen(repository) }
        composable(Routes.SETTINGS) { SettingsScreen(repository) }
        composable(Routes.BACKUP) { BackupScreen(repository) }
    }
    } // Box (отступ от статус-бара)
}
