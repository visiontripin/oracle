package com.botcontrol.admin.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Secrets (JWT) live in EncryptedSharedPreferences (AndroidKeyStore-backed).
 * Non-secret settings (base URL, username) live in DataStore.
 * Tokens are NEVER written to logs, Room or plain prefs.
 */
class AuthStore(context: Context) {

    private val appContext = context.applicationContext

    private val encrypted: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "secure_auth",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    suspend fun token(): String? = encrypted.getString(KEY_TOKEN, null)

    suspend fun saveSession(token: String, username: String, role: String, baseUrl: String) {
        encrypted.edit().putString(KEY_TOKEN, token).apply()
        appContext.appDataStore.edit { prefs ->
            prefs[KEY_USERNAME] = username
            prefs[KEY_ROLE] = role
            prefs[KEY_BASE_URL] = baseUrl.trim().trimEnd('/')
        }
    }

    suspend fun clearSession() {
        encrypted.edit().remove(KEY_TOKEN).apply()
    }

    suspend fun baseUrl(): String =
        appContext.appDataStore.data.map { it[KEY_BASE_URL].orEmpty() }.first()

    suspend fun username(): String =
        appContext.appDataStore.data.map { it[KEY_USERNAME].orEmpty() }.first()

    suspend fun role(): String =
        appContext.appDataStore.data.map { it[KEY_ROLE].orEmpty() }.first()

    suspend fun setBaseUrl(url: String) {
        appContext.appDataStore.edit { it[KEY_BASE_URL] = url.trim().trimEnd('/') }
    }

    private companion object {
        const val KEY_TOKEN = "jwt"
        val KEY_USERNAME = stringPreferencesKey("username")
        val KEY_ROLE = stringPreferencesKey("role")
        val KEY_BASE_URL = stringPreferencesKey("base_url")
    }
}
