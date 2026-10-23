package com.botcontrol.admin.data

import com.botcontrol.admin.data.local.BotRuleDao
import com.botcontrol.admin.data.local.BotRuleEntity
import com.botcontrol.admin.data.local.DraftDao
import com.botcontrol.admin.data.local.DraftEntity
import com.botcontrol.admin.data.remote.ApiClient
import com.botcontrol.admin.data.remote.ApiService
import com.botcontrol.admin.data.remote.LlmBindingCreateDto
import com.botcontrol.admin.data.remote.LlmChatRequestDto
import com.botcontrol.admin.data.remote.LlmJobErrorDto
import com.botcontrol.admin.data.remote.LlmJobResultDto
import com.botcontrol.admin.data.remote.LlmProfileDto
import com.botcontrol.admin.data.remote.LlmProviderCreateDto
import com.botcontrol.admin.data.remote.LoginRequest
import com.botcontrol.admin.data.remote.PluginCreateDto
import com.botcontrol.admin.data.remote.PluginUpdateDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response

/** Thin wrapper turning Retrofit responses into Kotlin Results. */
class BotRepository(
    private val apiClient: ApiClient,
    private val drafts: DraftDao,
    private val rules: BotRuleDao? = null,
) {
    private lateinit var authStore: AuthStore
    private var baseUrl: String = ""

    /** Must be called once from NavGraph with the current session values. */
    fun attach(authStore: AuthStore, baseUrl: String) {
        this.authStore = authStore
        this.baseUrl = baseUrl
    }

    fun updateBaseUrl(url: String) {
        baseUrl = url
    }

    private fun api(): ApiService = apiClient.service(baseUrl.ifBlank { "http://localhost:8000" })

    private suspend fun <T> call(block: suspend ApiService.() -> Response<T>): Result<T> =
        withContext(Dispatchers.IO) {
            try {
                val resp = api().block()
                if (resp.isSuccessful) {
                    val body = resp.body()
                    if (body != null) Result.success(body)
                    else Result.failure(ApiException(resp.code(), "Empty response"))
                } else {
                    Result.failure(ApiException(resp.code(), resp.errorBody()?.string().orEmpty()))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun login(base: String, username: String, password: String) = withContext(Dispatchers.IO) {
        try {
            val resp = apiClient.service(base).login(LoginRequest(username, password))
            if (resp.isSuccessful && resp.body() != null) Result.success(resp.body()!!)
            else Result.failure(ApiException(resp.code(), resp.errorBody()?.string().orEmpty()))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun logout() = call { logout() }
    suspend fun status() = call { status() }
    suspend fun botStart() = call { botStart() }
    suspend fun botStop() = call { botStop() }
    suspend fun botRestart() = call { botRestart() }
    suspend fun plugins() = call { plugins() }
    suspend fun createPlugin(body: PluginCreateDto) = call { createPlugin(body) }
    suspend fun plugin(id: String) = call { plugin(id) }
    suspend fun updatePlugin(id: String, body: PluginUpdateDto) = call { updatePlugin(id, body) }
    suspend fun deletePlugin(id: String) = call { deletePlugin(id) }
    suspend fun enablePlugin(id: String) = call { enablePlugin(id) }
    suspend fun disablePlugin(id: String) = call { disablePlugin(id) }
    suspend fun reloadPlugin(id: String) = call { reloadPlugin(id) }
    suspend fun pluginLogs(id: String, level: String? = null) = call { pluginLogs(id, level) }
    suspend fun pluginVersions(id: String) = call { pluginVersions(id) }
    suspend fun logs(level: String? = null, search: String? = null) = call { logs(level, search) }
    suspend fun audit() = call { audit() }
    suspend fun config() = call { config() }
    suspend fun updateConfig(values: Map<String, String>) =
        call { updateConfig(com.botcontrol.admin.data.remote.ConfigUpdateDto(values)) }
    suspend fun backupCreate(label: String = "manual") = call { backupCreate(label) }
    suspend fun backups() = call { backups() }
    suspend fun rollback(version: String) = call { rollback(version) }

    // ---------- LLM ----------
    suspend fun llmStatus() = call { llmStatus() }
    suspend fun llmProviders() = call { llmProviders() }
    suspend fun llmCreateProvider(body: LlmProviderCreateDto) = call { llmCreateProvider(body) }
    suspend fun llmUpdateProvider(id: String, body: LlmProviderCreateDto) = call { llmUpdateProvider(id, body) }
    suspend fun llmDeleteProvider(id: String) = call { llmDeleteProvider(id) }
    suspend fun llmActivateProvider(id: String) = call { llmActivateProvider(id) }
    suspend fun llmModels(id: String) = call { llmModels(id) }
    suspend fun llmTestProvider(id: String) = call { llmTestProvider(id) }
    suspend fun llmProfile() = call { llmProfile() }
    suspend fun llmUpdateProfile(body: LlmProfileDto) = call { llmUpdateProfile(body) }
    suspend fun llmBindings() = call { llmBindings() }
    suspend fun llmAddBinding(body: LlmBindingCreateDto) = call { llmAddBinding(body) }
    suspend fun llmUpdateBinding(id: String, body: LlmBindingCreateDto) = call { llmUpdateBinding(id, body) }
    suspend fun llmDeleteBinding(id: String) = call { llmDeleteBinding(id) }
    suspend fun llmChat(message: String) = call { llmChat(LlmChatRequestDto(message)) }
    suspend fun llmClaimJob() = call { llmClaimJob() }
    suspend fun llmJobResult(id: Int, reply: String) = call { llmJobResult(id, LlmJobResultDto(reply)) }
    suspend fun llmJobFail(id: Int, error: String) = call { llmJobFail(id, LlmJobErrorDto(error)) }
    suspend fun llmJobs(limit: Int = 30) = call { llmJobs(limit) }
    suspend fun llmAgentStatus() = call { llmAgentStatus() }

    // ---------- Local bot rules (on-phone bot mode) ----------
    suspend fun botRules(botId: Long = -1L): List<BotRuleEntity> {
        val dao = rules ?: return emptyList()
        return if (botId == -1L) dao.all() else dao.byBot(botId)
    }
    suspend fun saveBotRule(rule: BotRuleEntity): Long = rules?.upsert(rule) ?: 0L

    /** Удалить все правила бота (используется при сбросе настроек). */
    suspend fun deleteBotRules(botId: Long) {
        val dao = rules ?: return
        dao.byBot(botId).forEach { dao.delete(it.id) }
    }
    suspend fun deleteBotRule(id: Int) = rules?.delete(id) ?: Unit

    // ---------- Drafts (Room, offline code editing) ----------
    suspend fun saveDraft(pluginId: String, code: String, manifest: String, config: String) =
        withContext(Dispatchers.IO) {
            drafts.upsert(DraftEntity(pluginId = pluginId, code = code, manifest = manifest, config = config))
        }

    suspend fun loadDraft(pluginId: String): DraftEntity? = withContext(Dispatchers.IO) {
        drafts.get(pluginId)
    }

    suspend fun clearDraft(pluginId: String) = withContext(Dispatchers.IO) {
        drafts.delete(pluginId)
    }
}

class ApiException(val code: Int, message: String) : Exception("HTTP $code: ${message.take(500)}")
