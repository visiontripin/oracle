package com.botcontrol.admin.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.localBotDataStore: DataStore<Preferences> by preferencesDataStore(name = "localbot")

/**
 * Storage for the "bots on this phone" mode. НЕСКОЛЬКО ботов: профили
 * (метаданные в DataStore, токены — только в EncryptedSharedPreferences),
 * настройки каждого бота — в ключах с суффиксом botId.
 *
 * Обозначение botId = -1L значит «активный бот» (выбран в интерфейсе).
 */
class LocalBotStore(context: Context) {

    private val appContext = context.applicationContext

    /** Идентификатор активного бота (кэш поверх DataStore). */
    @Volatile
    var activeBotIdCache: Long = 0L
        private set

    private val encrypted: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "secure_local_bot",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private suspend fun resolveId(botId: Long): Long {
        if (botId >= 0) return botId
        if (activeBotIdCache != 0L) return activeBotIdCache
        val id = appContext.localBotDataStore.data.map { it[KEY_ACTIVE_BOT] ?: 0L }.first()
        activeBotIdCache = id
        return id
    }

    suspend fun setActiveBot(id: Long) {
        activeBotIdCache = id
        appContext.localBotDataStore.edit { it[KEY_ACTIVE_BOT] = id }
    }

    suspend fun activeBotId(): Long = resolveId(-1L)

    // ---------- режим старта (совместимость: "", "local", "server") ----------
    suspend fun mode(): String = appContext.localBotDataStore.data.map { it[KEY_MODE].orEmpty() }.first()

    suspend fun setMode(mode: String) {
        appContext.localBotDataStore.edit { it[KEY_MODE] = mode }
    }

    // ============================================================
    // ПРОФИЛИ БОТОВ
    // ============================================================

    suspend fun profiles(): List<BotProfile> =
        BotJson.load(
            appContext.localBotDataStore.data.map { it[KEY_PROFILES].orEmpty() }.first(),
            object : TypeToken<List<BotProfile>>() {},
            emptyList(),
        )

    suspend fun saveProfiles(list: List<BotProfile>) {
        appContext.localBotDataStore.edit { it[KEY_PROFILES] = BotJson.save(list) }
    }

    suspend fun addProfile(name: String, token: String, username: String): Long =
        withContext(Dispatchers.IO) {
            val current = profiles()
            val maxId = current.maxOfOrNull { it.id } ?: 0L
            val id = maxId + 1
            saveProfiles(current + BotProfile(id, name.ifBlank { "Бот $id" }, username, true))
            encrypted.edit().putString("token_$id", token).apply()
            if (current.isEmpty()) {
                // первый бот после старого одиночного режима: переносим настройки
                migrateLegacyTo(id)
            }
            if (current.isEmpty()) setMode("local")
            setActiveBot(id)
            id
        }

    suspend fun removeProfile(id: Long) = withContext(Dispatchers.IO) {
        saveProfiles(profiles().filterNot { it.id == id })
        encrypted.edit().remove("token_$id").apply()
        // Наборы, импортированные для этого бота, уходят вместе с ним;
        // общие наборы библиотеки (ownerBotId = 0) остаются.
        packs().let { all -> if (all.any { it.ownerBotId == id }) setPacks(all.filterNot { it.ownerBotId == id }) }
        if (activeBotIdCache == id || appContext.localBotDataStore.data
                .map { it[KEY_ACTIVE_BOT] ?: 0L }.first() == id) {
            val next = profiles().firstOrNull()?.id ?: 0L
            setActiveBot(next)
        }
    }

    suspend fun updateProfile(profile: BotProfile) {
        saveProfiles(profiles().map { if (it.id == profile.id) profile else it })
    }

    suspend fun profile(id: Long): BotProfile? = profiles().firstOrNull { it.id == id }

    suspend fun botToken(botId: Long = -1L): String? = withContext(Dispatchers.IO) {
        val id = resolveId(botId)
        encrypted.getString("token_$id", null) ?: if (id == 1L) encrypted.getString(KEY_BOT_TOKEN, null) else null
    }

    /** Совместимость со старым кодом (setup): сохранить токен активного/нового бота. */
    suspend fun saveBotToken(token: String, botUsername: String, botId: Long = -1L): Long =
        withContext(Dispatchers.IO) {
            val id = resolveId(botId)
            if (id == 0L) {
                // ещё нет профилей — создаём первый
                addProfile("Бот 1", token, botUsername)
            } else {
                encrypted.edit().putString("token_$id", token).apply()
                updateProfile(
                    (profile(id) ?: BotProfile(id, "Бот $id"))
                        .copy(username = botUsername.ifBlank {
                            profile(id)?.username.orEmpty()
                        }),
                )
                id
            }
        }

    suspend fun botUsername(botId: Long = -1L): String {
        val id = resolveId(botId)
        return profile(id)?.username.orEmpty()
    }

    /** Однократный перенос настроек старого «одиночного» бота в профиль. */
    private suspend fun migrateLegacyTo(newId: Long) {
        val prefs = appContext.localBotDataStore
        val data = prefs.data.first()
        suspend fun <T> move(key: Preferences.Key<T>, suffixed: Preferences.Key<T>) {
            data[key]?.let { v -> appContext.localBotDataStore.edit { it[suffixed] = v } }
        }
        move(KEY_SCHEDULE, schedKey(newId))
        move(KEY_KEYBOARD, kbKey(newId))
        move(KEY_SYSTEM_PROMPT, promptKey(newId))
        move(KEY_DEFAULT_REPLY, defaultKey(newId))
        move(KEY_REMINDERS_ON, remKey(newId))
        move(KEY_LAST_CHAT, lastChatKey(newId))
        move(KEY_MENU, menuKey(newId))
        move(KEY_TYPING, typingKey(newId))
        move(KEY_COOLDOWN, cooldownKey(newId))
        move(KEY_HISTORY, historyKey(newId))
        move(KEY_CLARIFY_ON, clarOnKey(newId))
        move(KEY_CLARIFY, clarKey(newId))
        move(KEY_LLM_ENABLED, llmKey(newId))

        // Параметры ИИ из старого DevicePrefs (SharedPreferences llm_device)
        val legacyPrefs = appContext.getSharedPreferences("llm_device", Context.MODE_PRIVATE)
        legacyPrefs.getString("selected_model", null)?.let { m ->
            appContext.localBotDataStore.edit { it[aiModelKey(newId)] = m }
        }
        val legacyTemp = legacyPrefs.getFloat("temperature", Float.NaN)
        if (!legacyTemp.isNaN()) {
            appContext.localBotDataStore.edit { it[aiTempKey(newId)] = legacyTemp }
        }
        val legacyTopK = legacyPrefs.getInt("top_k", -1)
        if (legacyTopK >= 0) {
            appContext.localBotDataStore.edit { it[aiTopKKey(newId)] = legacyTopK }
        }
        val legacyMaxTok = legacyPrefs.getInt("max_tokens", -1)
        if (legacyMaxTok > 0) {
            appContext.localBotDataStore.edit { it[aiMaxTokKey(newId)] = legacyMaxTok }
        }
        // Наборы ответов остаются общими (библиотека), ключ KEY_PACKS не переносим.
    }

    // ---------- параметры ИИ (на каждого бота свои) ----------
    suspend fun aiModel(botId: Long = -1L): String {
        val id = resolveId(botId)
        return appContext.localBotDataStore.data.map { it[aiModelKey(id)].orEmpty() }.first()
    }

    suspend fun setAiModel(value: String, botId: Long = -1L) {
        val id = resolveId(botId)
        appContext.localBotDataStore.edit { it[aiModelKey(id)] = value }
    }

    suspend fun aiTemperature(botId: Long = -1L): Float {
        val id = resolveId(botId)
        return appContext.localBotDataStore.data.map { it[aiTempKey(id)] ?: 0.8f }.first()
    }

    suspend fun setAiTemperature(value: Float, botId: Long = -1L) {
        val id = resolveId(botId)
        appContext.localBotDataStore.edit { it[aiTempKey(id)] = value }
    }

    suspend fun aiTopK(botId: Long = -1L): Int {
        val id = resolveId(botId)
        return appContext.localBotDataStore.data.map { it[aiTopKKey(id)] ?: 40 }.first()
    }

    suspend fun setAiTopK(value: Int, botId: Long = -1L) {
        val id = resolveId(botId)
        appContext.localBotDataStore.edit { it[aiTopKKey(id)] = value }
    }

    suspend fun aiMaxTokens(botId: Long = -1L): Int {
        val id = resolveId(botId)
        return appContext.localBotDataStore.data.map { it[aiMaxTokKey(id)] ?: 1024 }.first()
    }

    suspend fun setAiMaxTokens(value: Int, botId: Long = -1L) {
        val id = resolveId(botId)
        appContext.localBotDataStore.edit { it[aiMaxTokKey(id)] = value }
    }

    // ---------- расписание напоминаний (на бота) ----------
    suspend fun remindersOn(botId: Long = -1L): Boolean {
        val id = resolveId(botId)
        return appContext.localBotDataStore.data.map { it[remKey(id)] ?: false }.first()
    }

    suspend fun setRemindersOn(value: Boolean, botId: Long = -1L) {
        val id = resolveId(botId)
        appContext.localBotDataStore.edit { it[remKey(id)] = value }
    }

    suspend fun schedule(botId: Long = -1L): List<ScheduleEvent> {
        val id = resolveId(botId)
        val own = appContext.localBotDataStore.data.map { it[schedKey(id)].orEmpty() }.first()
        return BotJson.scheduleList(own)
    }

    suspend fun setSchedule(events: List<ScheduleEvent>, botId: Long = -1L) {
        val id = resolveId(botId)
        appContext.localBotDataStore.edit { it[schedKey(id)] = BotJson.save(events) }
    }

    /** Куда слать напоминания: последний чат, где писали боту. */
    suspend fun lastChatId(botId: Long = -1L): Long {
        val id = resolveId(botId)
        return appContext.localBotDataStore.data.map { it[lastChatKey(id)] ?: 0L }.first()
    }

    suspend fun setLastChatId(value: Long, botId: Long = -1L) {
        val id = resolveId(botId)
        appContext.localBotDataStore.edit { it[lastChatKey(id)] = value }
    }

    // ---------- клавиатура (на бота) ----------
    suspend fun keyboard(botId: Long = -1L): List<String> {
        val id = resolveId(botId)
        val raw = appContext.localBotDataStore.data.map { it[kbKey(id)].orEmpty() }.first()
        if (raw.isBlank()) return emptyList()
        return BotJson.stringList(raw)
    }

    suspend fun setKeyboard(labels: List<String>, botId: Long = -1L) {
        val id = resolveId(botId)
        appContext.localBotDataStore.edit {
            it[kbKey(id)] = com.google.gson.Gson().toJson(labels.take(12).map { l -> l.take(32) })
        }
    }

    /**
     * Полный сброс настроек бота к заводским: удаляются правила ответов,
     * расписание, клавиатура, характер и параметры ИИ, уточняющие вопросы,
     * меню команд, напоминания, канал и объявления. Профиль бота (имя/токен)
     * и общая библиотека наборов остаются.
     */
    suspend fun resetBot(botId: Long) {
        appContext.localBotDataStore.edit {
            listOf(
                schedKey(botId), kbKey(botId), promptKey(botId), defaultKey(botId),
                remKey(botId), lastChatKey(botId), menuKey(botId),
                typingKey(botId), cooldownKey(botId), historyKey(botId),
                clarOnKey(botId), clarKey(botId), llmKey(botId),
                aiModelKey(botId), aiTempKey(botId), aiTopKKey(botId), aiMaxTokKey(botId),
                chanKey(botId), listOnKey(botId), listingsKey(botId),
            ).forEach { key -> it.remove(key) }
        }
    }

    // ---------- канал публикаций и режим объявлений ----------

    private fun chanKey(id: Long) = stringPreferencesKey("channel_$id")
    private fun listOnKey(id: Long) = booleanPreferencesKey("listings_on_$id")
    private fun listingsKey(id: Long) = stringPreferencesKey("listings_$id")

    /** Канал публикаций, например «@daromvpl». Пусто = не публиковать. */
    suspend fun channelId(botId: Long = -1L): String {
        val id = resolveId(botId)
        return appContext.localBotDataStore.data.map { it[chanKey(id)].orEmpty() }.first()
    }

    suspend fun setChannelId(value: String, botId: Long = -1L) {
        val id = resolveId(botId)
        appContext.localBotDataStore.edit { it[chanKey(id)] = value.trim() }
    }

    /** Режим объявлений: визард публикации + «Мои объявления» + статистика. */
    suspend fun listingsOn(botId: Long = -1L): Boolean {
        val id = resolveId(botId)
        return appContext.localBotDataStore.data.map { it[listOnKey(id)] ?: false }.first()
    }

    suspend fun setListingsOn(value: Boolean, botId: Long = -1L) {
        val id = resolveId(botId)
        appContext.localBotDataStore.edit { it[listOnKey(id)] = value }
    }

    suspend fun listings(botId: Long = -1L): List<Listing> {
        val id = resolveId(botId)
        val raw = appContext.localBotDataStore.data.map { it[listingsKey(id)].orEmpty() }.first()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            com.google.gson.Gson().fromJson(raw, Array<Listing>::class.java).toList()
        }.getOrDefault(emptyList())
    }

    suspend fun setListings(value: List<Listing>, botId: Long = -1L) {
        val id = resolveId(botId)
        appContext.localBotDataStore.edit {
            it[listingsKey(id)] = com.google.gson.Gson().toJson(value)
        }
    }

    // ---------- наборы ответов (общая библиотека) ----------
    suspend fun packs(): List<ReplyPack> =
        BotJson.packList(appContext.localBotDataStore.data.map { it[KEY_PACKS].orEmpty() }.first())

    suspend fun setPacks(value: List<ReplyPack>) {
        appContext.localBotDataStore.edit { it[KEY_PACKS] = BotJson.save(value) }
    }

    // ---------- характер и ИИ (на бота) ----------
    /**
     * ИИ ВКЛЮЧАЕТСЯ ТОЛЬКО ПО НЕОБХОДИМОСТИ: по умолчанию выключен.
     * Даже при вкл. ИИ не отвечает «на всё»: только по правилам с действием
     * «ИИ» и по явной команде /chat. Функции бота работают кодом (правила,
     * наборы, скрипты, расписание) без ИИ.
     */
    suspend fun llmEnabled(botId: Long = -1L): Boolean {
        val id = resolveId(botId)
        return appContext.localBotDataStore.data.map { it[llmKey(id)] ?: false }.first()
    }

    suspend fun setLlmEnabled(value: Boolean, botId: Long = -1L) {
        val id = resolveId(botId)
        appContext.localBotDataStore.edit { it[llmKey(id)] = value }
    }

    suspend fun systemPrompt(botId: Long = -1L): String {
        val id = resolveId(botId)
        return appContext.localBotDataStore.data
            .map { it[promptKey(id)].orEmpty() }.first()
    }

    suspend fun setSystemPrompt(value: String, botId: Long = -1L) {
        val id = resolveId(botId)
        appContext.localBotDataStore.edit { it[promptKey(id)] = value.take(16000) }
    }

    suspend fun defaultReply(botId: Long = -1L): String {
        val id = resolveId(botId)
        return appContext.localBotDataStore.data
            .map { it[defaultKey(id)].orEmpty() }.first()
    }

    suspend fun setDefaultReply(value: String, botId: Long = -1L) {
        val id = resolveId(botId)
        appContext.localBotDataStore.edit { it[defaultKey(id)] = value.take(4000) }
    }

    // ---------- главное меню Telegram (на бота) ----------
    suspend fun menuCommands(botId: Long = -1L): List<MenuCommand> {
        val id = resolveId(botId)
        val own = appContext.localBotDataStore.data.map { it[menuKey(id)].orEmpty() }.first()
        return BotJson.menuCommandList(own)
    }

    suspend fun setMenuCommands(value: List<MenuCommand>, botId: Long = -1L) {
        val id = resolveId(botId)
        appContext.localBotDataStore.edit { it[menuKey(id)] = BotJson.save(value) }
    }

    // ---------- параметры разговорного режима (на бота) ----------
    suspend fun typingSeconds(botId: Long = -1L): Int {
        val id = resolveId(botId)
        return appContext.localBotDataStore.data.map { it[typingKey(id)] ?: 0 }.first()
    }

    suspend fun setTypingSeconds(value: Int, botId: Long = -1L) {
        val id = resolveId(botId)
        appContext.localBotDataStore.edit { it[typingKey(id)] = value.coerceIn(0, 10) }
    }

    suspend fun cooldownSec(botId: Long = -1L): Int {
        val id = resolveId(botId)
        return appContext.localBotDataStore.data.map { it[cooldownKey(id)] ?: 0 }.first()
    }

    suspend fun setCooldownSec(value: Int, botId: Long = -1L) {
        val id = resolveId(botId)
        appContext.localBotDataStore.edit { it[cooldownKey(id)] = value.coerceIn(0, 600) }
    }

    suspend fun historyLimit(botId: Long = -1L): Int {
        val id = resolveId(botId)
        return appContext.localBotDataStore.data.map { it[historyKey(id)] ?: 4 }.first()
    }

    suspend fun setHistoryLimit(value: Int, botId: Long = -1L) {
        val id = resolveId(botId)
        appContext.localBotDataStore.edit { it[historyKey(id)] = value.coerceIn(0, 20) }
    }

    suspend fun clarifyEnabled(botId: Long = -1L): Boolean {
        val id = resolveId(botId)
        return appContext.localBotDataStore.data.map { it[clarOnKey(id)] ?: true }.first()
    }

    suspend fun setClarifyEnabled(value: Boolean, botId: Long = -1L) {
        val id = resolveId(botId)
        appContext.localBotDataStore.edit { it[clarOnKey(id)] = value }
    }

    suspend fun clarifyQuestions(botId: Long = -1L): List<String> {
        val id = resolveId(botId)
        val own = appContext.localBotDataStore.data.map { it[clarKey(id)].orEmpty() }.first()
        return BotJson.stringList(own)
    }

    suspend fun setClarifyQuestions(value: List<String>, botId: Long = -1L) {
        val id = resolveId(botId)
        appContext.localBotDataStore.edit { it[clarKey(id)] = BotJson.save(value.take(50)) }
    }

    private companion object {
        const val KEY_BOT_TOKEN = "bot_token"
        val KEY_MODE = stringPreferencesKey("mode")
        val KEY_BOT_USERNAME = stringPreferencesKey("bot_username")
        val KEY_LLM_ENABLED = booleanPreferencesKey("llm_enabled")
        val KEY_SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val KEY_DEFAULT_REPLY = stringPreferencesKey("default_reply")
        val KEY_KEYBOARD = stringPreferencesKey("keyboard")
        val KEY_REMINDERS_ON = booleanPreferencesKey("reminders_on")
        val KEY_SCHEDULE = stringPreferencesKey("schedule")
        val KEY_LAST_CHAT = longPreferencesKey("last_chat_id")
        val KEY_PACKS = stringPreferencesKey("reply_packs")
        val KEY_TYPING = intPreferencesKey("typing_seconds")
        val KEY_COOLDOWN = intPreferencesKey("cooldown_sec")
        val KEY_HISTORY = intPreferencesKey("history_limit")
        val KEY_CLARIFY_ON = booleanPreferencesKey("clarify_enabled")
        val KEY_CLARIFY = stringPreferencesKey("clarify_questions")
        val KEY_MENU = stringPreferencesKey("menu_commands")
        val KEY_PROFILES = stringPreferencesKey("bot_profiles")
        val KEY_ACTIVE_BOT = longPreferencesKey("active_bot_id")

        // ключи с суффиксом botId
        private fun schedKey(id: Long) = stringPreferencesKey("schedule_$id")
        private fun kbKey(id: Long) = stringPreferencesKey("keyboard_$id")
        private fun promptKey(id: Long) = stringPreferencesKey("system_prompt_$id")
        private fun defaultKey(id: Long) = stringPreferencesKey("default_reply_$id")
        private fun remKey(id: Long) = booleanPreferencesKey("reminders_on_$id")
        private fun lastChatKey(id: Long) = longPreferencesKey("last_chat_id_$id")
        private fun menuKey(id: Long) = stringPreferencesKey("menu_commands_$id")
        private fun typingKey(id: Long) = intPreferencesKey("typing_seconds_$id")
        private fun cooldownKey(id: Long) = intPreferencesKey("cooldown_sec_$id")
        private fun historyKey(id: Long) = intPreferencesKey("history_limit_$id")
        private fun clarOnKey(id: Long) = booleanPreferencesKey("clarify_enabled_$id")
        private fun clarKey(id: Long) = stringPreferencesKey("clarify_questions_$id")
        private fun llmKey(id: Long) = booleanPreferencesKey("llm_enabled_$id")
        private fun aiModelKey(id: Long) = stringPreferencesKey("ai_model_$id")
        private fun aiTempKey(id: Long) = floatPreferencesKey("ai_temperature_$id")
        private fun aiTopKKey(id: Long) = intPreferencesKey("ai_top_k_$id")
        private fun aiMaxTokKey(id: Long) = intPreferencesKey("ai_max_tokens_$id")
    }
}

/** Профиль бота: метаданные. Токен хранится отдельно — в шифрованном хранилище. */
data class BotProfile(
    val id: Long = 0L,
    val name: String = "",
    val username: String = "",
    val enabled: Boolean = true,
)

