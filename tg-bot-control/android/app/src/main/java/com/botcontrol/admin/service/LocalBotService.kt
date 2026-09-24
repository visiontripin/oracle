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
import com.botcontrol.admin.data.BotJson
import com.botcontrol.admin.data.InlineBtn
import com.botcontrol.admin.data.withIds
import com.botcontrol.admin.data.LocalBotStore
import com.botcontrol.admin.data.telegram.TelegramApi
import com.botcontrol.admin.data.telegram.TgCallback
import com.botcontrol.admin.data.telegram.TgMessage
import com.botcontrol.admin.llm.BotScript
import com.botcontrol.admin.llm.DeviceLlm
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class LocalBotState(
    val botId: Long = 0L,
    val running: Boolean = false,
    val botUsername: String = "",
    val processed: Int = 0,
    val lastError: String = "",
    val lastPollAt: Long = 0L, // epoch seconds of the last SUCCESSFUL getUpdates
    val remindersOn: Boolean = false,
    /** Нерешённые проблемы бота: тег -> текст. Висят до устранения причины. */
    val problems: Map<String, String> = emptyMap(),
)

/**
 * The WHOLE bot on the phone: long-polls Telegram, answers with rules,
 * scheduled reminders (with inline menus), scripts and the on-device LLM
 * with dialog context. No external server, no login, no Termux.
 *
 * Reliability (v1.0.3, keep): fresh scope per start, auto deleteWebhook,
 * silent backlog confirm, clear Russian errors, lastPollAt diagnostics.
 */
class LocalBotService : Service() {

    private var scope: CoroutineScope = newScope()


    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP_BOT) {
            // Остановить ОДНОГО бота: его циклы выходят по running=false.
            val botId = intent.getLongExtra(EXTRA_BOT_ID, 0L)
            if (botId > 0) setState(botId) { it.copy(running = false) }
            refreshNotification()
            return START_STICKY
        }
        if (intent?.action == ACTION_START_BOT) {
            // Запустить ОДНОГО конкретного бота.
            startInForeground()
            ensureScope()
            val botId = intent.getLongExtra(EXTRA_BOT_ID, 0L)
            if (botId > 0) scope.launch { startBotLoop(botId) }
            return START_STICKY
        }
        startInForeground()
        ensureScope()
        // Без действия: запускаем всех включённых ботов.
        scope.launch {
            val store = (application as BotControlApp).localStore
            val profiles = store.profiles().filter { it.enabled }
            if (profiles.isEmpty()) {
                notifyContent("Нет включённых ботов — добавь бота на главном экране")
            }
            profiles.forEach { profile ->
                scope.launch { startBotLoop(profile.id) }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun shutdown() {
        states.value = states.value.mapValues { it.value.copy(running = false) }
        scope.cancel()
    }

    /** Scope пересоздаём только если он был отменён (глобальный стоп). */
    private fun ensureScope() {
        if (!scope.isActive) scope = newScope()
    }

    /** Запуск одного бота: защита от двойного запуска и выключенных профилей. */
    private suspend fun startBotLoop(botId: Long) {
        val store = (application as BotControlApp).localStore
        val profile = store.profiles().firstOrNull { it.id == botId }
        if (profile == null || !profile.enabled) {
            setState(botId) { it.copy(running = false, lastError = "Бот выключен или удалён") }
            return
        }
        if (states.value[botId]?.running == true) return // уже работает
        val existing = states.value[botId]
        states.value = states.value + (botId to
            (existing ?: LocalBotState(botId = botId)).copy(running = true, lastError = ""))
        runBotLoop(botId)
    }

    private suspend fun runBotLoop(botId: Long) {
        val app = application as BotControlApp
        val store = app.localStore
        val token = store.botToken(botId)
        if (token.isNullOrBlank()) {
            setState(botId) { it.copy(running = false, lastError = "Токен бота не задан") }
            return
        }
        val api = TelegramApi(token)
        var botName = ""
        var reason = ""
        api.validateToken().onSuccess { name ->
            botName = name
            store.updateProfile(
                (store.profile(botId) ?: com.botcontrol.admin.data.BotProfile(botId))
                    .copy(username = name))
        }.onFailure { reason = it.message ?: "ошибка сети" }
        if (reason.isNotBlank() && reason.containsAny("401", "404")) {
            addProblem(botId, "token", reason)
            setState(botId) { it.copy(running = false, lastError = reason) }
            return
        }
        if (botName.isBlank()) {
            // сеть недоступна: остаёмся в фоне и ретраим каждые 15 секунд
            setState(botId) {
                it.copy(lastError = reason.ifBlank { "ошибка сети" } + " — повторяем каждые 15 с")
            }
            addProblem(botId, "network", reason.ifBlank { "нет связи" })
            while (scope.isActive && states.value[botId]?.running == true && botName.isBlank()) {
                delay(15_000)
                api.validateToken().onSuccess { name ->
                    botName = name
                    store.updateProfile(
                        (store.profile(botId) ?: com.botcontrol.admin.data.BotProfile(botId))
                            .copy(username = name))
                    setState(botId) { it.copy(botUsername = name, lastError = "") }
                    resolveProblem(botId, "network")
                }.onFailure { e ->
                    val r = e.message ?: "ошибка сети"
                    if (r.containsAny("401", "404")) {
                        addProblem(botId, "token", r)
                        setState(botId) { it.copy(running = false, lastError = r) }
                        return
                    }
                    setState(botId) { it.copy(lastError = r) }
                }
            }
            if (states.value[botId]?.running != true) return
        }
        setState(botId) { it.copy(botUsername = botName, running = true, lastError = "") }
        DeviceLlm.log("🚀 Бот @$botName запущен (правил: ${runCatching { app.repository.botRules(botId).size }.getOrDefault(-1)})")
        refreshNotification()

        // Главное меню Telegram (кнопка «Меню») — применяем сохранённый список.
        val menuCommands = store.menuCommands(botId)
        if (menuCommands.isNotEmpty()) {
            api.setMyCommands(menuCommands.map { it.command to it.description })
                .onSuccess {
                    DeviceLlm.log("📜 [$botName] Главное меню применено (${menuCommands.size} команд)")
                    resolveProblem(botId, "menu")
                }
                .onFailure { e ->
                    DeviceLlm.log("⚠️ [$botName] Меню не применено: ${e.message?.take(160)}")
                    addProblem(botId, "menu", "Меню Telegram: ${e.message?.take(140)}")
                }
        }

        // A leftover webhook permanently blocks getUpdates — remove it (no-op if none).
        api.deleteWebhook()

        // Confirm any backlog silently: no reply spam to messages sent before start.
        var offset = runCatching { api.getUpdates(0, 0).third }.getOrDefault(0L)

        // Планировщик напоминаний этого бота живёт рядом с опросом.
        scope.launch { schedulerLoop(botId, store, api) }

        while (scope.isActive && states.value[botId]?.running == true) {
            try {
                val (messages, callbacks, nextOffset) = api.getUpdates(offset)
                offset = nextOffset
                setState(botId) {
                    it.copy(
                        lastPollAt = System.currentTimeMillis() / 1000, lastError = "",
                    )
                }
                resolveProblem(botId, "network")
                resolveProblem(botId, "conflict")
                messages.forEach { msg ->
                    handle(app, store, api, botId, msg)
                    setState(botId) { it.copy(processed = it.processed + 1) }
                }
                callbacks.forEach { cb ->
                    handleCallback(app, store, api, botId, cb)
                    setState(botId) { it.copy(processed = it.processed + 1) }
                }
                refreshNotification()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                val reason2 = when {
                    msg.contains("409") || msg.contains("Conflict", true) ||
                        msg.contains("webhook", true) ->
                        "конфликт: токен уже опрашивается другим приложением. Останови второй экземпляр"
                    else -> msg.ifBlank { "ошибка сети" }
                }
                setState(botId) { it.copy(lastError = reason2) }
                addProblem(botId, if (reason2.contains("конфликт")) "conflict" else "network", reason2)
                delay(POLL_ERROR_MS)
            }
        }
    }

    private fun setState(botId: Long, update: (LocalBotState) -> LocalBotState) {
        val current = states.value[botId] ?: LocalBotState(botId = botId)
        states.value = states.value + (botId to update(current))
    }

    /** Проблема висит, пока причина не устранена (снимается resolveProblem). */
    private fun addProblem(botId: Long, tag: String, text: String) {
        setState(botId) {
            if (it.problems[tag] == text) it
            else it.copy(problems = it.problems + (tag to text))
        }
    }

    private fun resolveProblem(botId: Long, tag: String) {
        setState(botId) {
            if (tag in it.problems) it.copy(problems = it.problems - tag) else it
        }
    }

    private fun refreshNotification() {
        val all = states.value.values
        val running = all.count { it.running }
        val processed = all.sumOf { it.processed }
        notifyContent(
            if (running > 1) "Ботов работает: $running • сообщений: $processed"
            else if (running == 1) {
                val bot = all.first { it.running }
                "Бот @${bot.botUsername} работает • сообщений: ${bot.processed}"
            } else "Боты остановлены"
        )
    }

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { contains(it, ignoreCase = true) }

    // ---------- входящее сообщение ----------

    private suspend fun handle(
        app: BotControlApp,
        store: LocalBotStore,
        api: TelegramApi,
        botId: Long,
        msg: TgMessage,
    ) {
        store.setLastChatId(msg.chatId, botId)
        val text = if (msg.text.isBlank() && msg.kind.isNotBlank()) "[${msg.kind}]" else msg.text.trim()
        if (text.isEmpty() && msg.photoId.isBlank()) return
        val keyboard = store.keyboard(botId)

        // Режим «Объявления»: визард/статистика перехватывают текст и фото.
        if (store.listingsOn(botId)) {
            val consumed = ListingEngine.handleText(
                context = this,
                store = store, api = api, botId = botId,
                chatId = msg.chatId, firstName = msg.firstName,
                text = text, photoId = msg.photoId, replyKeyboard = keyboard,
            )
            if (consumed) return
        }

        // Решение принимает общий «мозг» (тот же, что во вкладке имитации).
        DeviceLlm.log("📩 Сообщение: '${text.take(80)}'")
        val decision = BotBrain.decide(
            context = this,
            store = store,
            botId = botId,
            chatId = msg.chatId,
            firstName = msg.firstName,
            text = text,
            respectCooldown = true,
            onLlmStart = { api.sendChatAction(msg.chatId) },
            trace = { DeviceLlm.log("   • $it") },
        )
        DeviceLlm.log("💬 Источник: ${decision.source}; ответ: '${decision.reply.take(60)}'")
        if (decision.reply.isBlank()) return
        // Для ответа ИИ «печатает…» уже показано во время генерации — не дублируем паузу.
        if (decision.source != "ИИ") withTyping(api, store, botId, msg.chatId)
        api.sendMessage(msg.chatId, decision.reply, keyboard, decision.menu)
            .onFailure { DeviceLlm.log("❌ Не отправлено (${decision.source}): ${it.message ?: "?"}") }
    }

    /** «Печатает…» + пауза-имитация набора (как typing_plugin, сек из настроек бота). */
    private suspend fun withTyping(api: TelegramApi, store: LocalBotStore, botId: Long, chatId: Long) {
        val secs = store.typingSeconds(botId)
        if (secs <= 0) return
        api.sendChatAction(chatId)
        delay(secs * 1000L)
    }

    // ---------- inline-кнопки (callback_query) ----------

    private suspend fun handleCallback(
        app: BotControlApp,
        store: LocalBotStore,
        api: TelegramApi,
        botId: Long,
        cb: TgCallback,
    ) {
        store.setLastChatId(cb.chatId, botId)

        // Кнопки режима объявлений (lst_*) обрабатывает ListingEngine.
        if (store.listingsOn(botId) && cb.data.startsWith("lst_")) {
            ListingEngine.handleCallback(
                context = this, store = store, api = api, botId = botId,
                chatId = cb.chatId, messageId = cb.messageId,
                callbackId = cb.callbackId, data = cb.data,
                firstName = cb.firstName, replyKeyboard = store.keyboard(botId),
            )
            return
        }

        // Ищем кнопку ТОЛЬКО среди меню этого бота (правила + события).
        val hit = findButton(app, store, botId, cb.data)
        val btn = hit?.first
        // Всегда отвечаем на callback, чтобы в Telegram не висели «часики».
        if (btn == null) {
            // Диагностика: что бот знает сейчас — видно в «Журнал событий».
            val known = LinkedHashSet<String>()
            app.repository.botRules(botId).forEach { rule ->
                known.addAll(BotBrain.parseMenu(rule.menu).map { it.id })
            }
            store.schedule(botId).forEach { event ->
                known.addAll(event.menu.withIds().map { it.id })
            }
            DeviceLlm.log("⚠️ Кнопка '${cb.data}' не найдена в меню этого бота. " +
                "Известно: ${known.ifEmpty { listOf("— (меню пусто — пересоздай правила/расписание)") }.joinToString(", ").take(300)}")
            api.answerCallbackQuery(
                cb.callbackId,
                "Кнопка устарела: пересыль /start или нажми кнопку в новом сообщении",
            )
            return
        }
        DeviceLlm.log("🔘 Кнопка «${btn.label}» (${btn.action})")
        api.answerCallbackQuery(cb.callbackId, btn.toast)

        val menu = hit?.second ?: listOf(btn)
        when (btn.action) {
            "pack" -> {
                val reply = BotBrain.randomFrom(store.packs(), btn.packId)
                withTyping(api, store, botId, cb.chatId)
                // В оригинале сарказм присылался НОВЫМ сообщением,
                // но если в коде стоит правка — правим сообщение под кнопкой.
                if (btn.edit) api.editMessageText(cb.chatId, cb.messageId, reply, menu)
                else api.sendMessage(cb.chatId, reply)
            }
            "script" -> {
                BotScript.run(btn.script, cb.firstName, btn.label, cb.chatId)
                    .onSuccess { reply ->
                        withTyping(api, store, botId, cb.chatId)
                        api.sendMessage(cb.chatId, reply.ifBlank { "…" })
                    }
                    .onFailure { e ->
                        api.sendMessage(cb.chatId, "Ошибка скрипта: ${e.message?.take(140)}")
                    }
            }
            "reminders_on", "reminders_off" -> {
                val on = btn.action == "reminders_on"
                if (store.remindersOn(botId) == on) {
                    // Уже в этом состоянии — как в оригинале: только всплывашка.
                    api.answerCallbackQuery(
                        cb.callbackId,
                        btn.toastNoChange.ifBlank { "Уже ${if (on) "работает" else "остановлено"}" })
                    return
                }
                store.setRemindersOn(on, botId)
                setState(botId) { it.copy(remindersOn = on) }
                DeviceLlm.log(
                    if (on) "▶️ Напоминания включены (кнопкой в Telegram)"
                    else "⏹ Напоминания выключены (кнопкой в Telegram)")
                // Правим сообщение под кнопкой, как в оригинальном боте:
                // ВСЕ кнопки меню остаются — «Стоп»/«Запуск» работают дальше.
                api.editMessageText(
                    cb.chatId, cb.messageId,
                    btn.text.ifBlank {
                        if (on) "▶️ Напоминания включены." else "⏹ Напоминания выключены."
                    },
                    inlineMenu = menu,
                )
            }
            "url" -> {
                // Кнопка-ссылка: Telegram сам открывает url, боту делать
                // нечего (всплывашка уже отправлена). Раньше сюда падало
                // и присылали пустой текст «…».
            }
            else -> {
                val reply = btn.text.ifBlank { "…" }
                withTyping(api, store, botId, cb.chatId)
                if (btn.edit) api.editMessageText(cb.chatId, cb.messageId, reply, menu)
                else api.sendMessage(cb.chatId, reply)
            }
        }
    }

    /**
     * Кнопка по её callback_data: (сама кнопка, всё меню сообщения).
     * Ищем только среди меню ЭТОГО бота — иначе кнопка одного бота
     * срабатывала бы у другого и чужие кнопки «не работали».
     */
    private suspend fun findButton(
        app: BotControlApp,
        store: LocalBotStore,
        botId: Long,
        data: String,
    ): Pair<InlineBtn, List<InlineBtn>>? {
        for (rule in app.repository.botRules(botId)) {
            val menu = BotBrain.parseMenu(rule.menu)
            menu.firstOrNull { it.id == data }?.let { return it to menu }
        }
        for (event in store.schedule(botId)) {
            val menu = event.menu.withIds()
            menu.firstOrNull { it.id == data }?.let { return it to menu }
        }
        return null
    }

    // ---------- планировщик напоминаний ----------

    private suspend fun schedulerLoop(botId: Long, store: LocalBotStore, api: TelegramApi) {
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val sentToday = HashSet<String>()
        var curDate = ""
        while (scope.isActive && states.value[botId]?.running == true) {
            delay(20_000)
            try {
                if (!store.remindersOn(botId)) continue
                val chatId = store.lastChatId(botId)
                if (chatId == 0L) continue

                val cal = Calendar.getInstance()
                val today = dayFormat.format(cal.time)
                if (today != curDate) {
                    sentToday.clear()
                    curDate = today
                }
                // Calendar: SUNDAY=1 … SATURDAY=7 → Пн=1 … Вс=7
                val rawDow = cal.get(Calendar.DAY_OF_WEEK)
                val dow = if (rawDow == Calendar.SUNDAY) 7 else rawDow - 1

                val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
                val events = store.schedule(botId)
                    .filter { it.enabled && it.days.contains(dow) }
                    .sortedBy { it.hour * 60 + it.minute }
                for (e in events) {
                    val key = "${e.id}@$today"
                    if (key in sentToday) continue
                    val ageMin = nowMin - (e.hour * 60 + e.minute)
                    if (ageMin < 0) continue          // ещё впереди
                    if (ageMin > CATCH_UP_MINUTES) {  // слишком старое — тихо пропускаем
                        sentToday.add(key)
                        continue
                    }
                    if (e.toChannel) {
                        // Пост в канал публикаций (например, дайджест 10:20/20:40).
                        val channel = store.channelId(botId)
                        if (channel.isBlank()) {
                            DeviceLlm.log("⚠️ Событие ${e.timeLabel()} в канал, но канал не задан")
                        } else {
                            val variants = e.text.split(" | ").map { it.trim() }
                                .filter { it.isNotBlank() }
                            val dayIdx = cal.get(Calendar.DAY_OF_YEAR)
                            val postText = if (variants.isEmpty()) e.text
                                else variants[dayIdx % variants.size]
                            api.sendTo(channel, postText, e.menu.withIds())
                                .onFailure { DeviceLlm.log("❌ Пост в канал не отправлен: ${it.message ?: "?"}") }
                            DeviceLlm.log("📢 Пост в канал $channel: ${postText.take(60)}")
                        }
                    } else {
                        withTyping(api, store, botId, chatId)
                        api.sendMessage(chatId, e.text, inlineMenu = e.menu.withIds())
                            .onFailure { DeviceLlm.log("❌ Напоминание не отправлено: ${it.message ?: "?"}") }
                        DeviceLlm.log("⏰ Напоминание ${e.timeLabel()}: ${e.text.take(60)}")
                    }
                    sentToday.add(key)
                    delay(400)
                }
                val remOn = store.remindersOn(botId)
                setState(botId) { it.copy(remindersOn = remOn) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DeviceLlm.log("⚠️ Планировщик: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    // ---------- notification ----------

    private fun startInForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Мой бот", NotificationManager.IMPORTANCE_LOW)
            )
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification("Запуск бота…"),
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
        private const val CHANNEL_ID = "local_bot"
        private const val NOTIFICATION_ID = 2001
        private const val POLL_ERROR_MS = 10_000L
        private const val CATCH_UP_MINUTES = 5L
        const val ACTION_STOP = "com.botcontrol.admin.STOP_LOCAL_BOT"
        const val ACTION_START_BOT = "com.botcontrol.admin.START_ONE_BOT"
        const val ACTION_STOP_BOT = "com.botcontrol.admin.STOP_ONE_BOT"
        const val EXTRA_BOT_ID = "bot_id"

        val states = MutableStateFlow<Map<Long, LocalBotState>>(emptyMap())

        /** Суммарный статус для уведомлений/виджетов. */
        fun anyRunning(): Boolean = states.value.values.any { it.running }

        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(
                context, Intent(context, LocalBotService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LocalBotService::class.java).setAction(ACTION_STOP))
        }

        /** Запустить одного бота. */
        fun startBot(context: Context, botId: Long) {
            androidx.core.content.ContextCompat.startForegroundService(
                context, Intent(context, LocalBotService::class.java)
                    .setAction(ACTION_START_BOT).putExtra(EXTRA_BOT_ID, botId))
        }

        /** Остановить одного бота (остальные продолжают). */
        fun stopBot(context: Context, botId: Long) {
            context.startService(
                Intent(context, LocalBotService::class.java)
                    .setAction(ACTION_STOP_BOT).putExtra(EXTRA_BOT_ID, botId))
        }
    }
}
