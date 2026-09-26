package com.botcontrol.admin.data.remote

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

// ---------- Auth ----------
data class LoginRequest(val username: String, val password: String)

data class LoginResponse(
    @SerializedName("access_token") val accessToken: String,
    val role: String,
    @SerializedName("expires_in_minutes") val expiresInMinutes: Int,
)

// ---------- Status ----------
data class StatusDto(
    val online: Boolean,
    @SerializedName("uptime_seconds") val uptimeSeconds: Int,
    val version: String,
    @SerializedName("active_plugins") val activePlugins: List<String>,
    @SerializedName("last_errors") val lastErrors: List<String>,
    @SerializedName("recent_events") val recentEvents: List<Map<String, JsonElement?>>,
)

data class MessageDto(val ok: Boolean = true, val message: String = "")

// ---------- Plugins ----------
data class PluginCommandDto(val name: String, val description: String = "")

data class PluginTriggerDto(
    val type: String,
    val pattern: String,
    @SerializedName("response_text") val responseText: String = "",
    val description: String = "",
)

data class PluginInfoDto(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val enabled: Boolean,
    val permissions: List<String> = emptyList(),
    val commands: List<PluginCommandDto> = emptyList(),
    val triggers: List<PluginTriggerDto> = emptyList(),
    @SerializedName("last_error") val lastError: String = "",
    @SerializedName("has_code") val hasCode: Boolean = false,
)

data class PluginDetailDto(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val enabled: Boolean,
    val permissions: List<String> = emptyList(),
    val commands: List<PluginCommandDto> = emptyList(),
    val triggers: List<PluginTriggerDto> = emptyList(),
    @SerializedName("last_error") val lastError: String = "",
    val manifest: Map<String, JsonElement?> = emptyMap(),
    val code: String = "",
    val config: Map<String, JsonElement?> = emptyMap(),
)

data class PluginCreateDto(
    val id: String,
    val name: String,
    val description: String = "",
    val version: String = "0.1.0",
    val permissions: List<String> = emptyList(),
    val commands: List<PluginCommandDto> = emptyList(),
    val triggers: List<PluginTriggerDto> = emptyList(),
    val code: String = "",
    val config: Map<String, JsonElement?> = emptyMap(),
)

data class PluginUpdateDto(
    val manifest: Map<String, JsonElement?>? = null,
    val code: String? = null,
    val config: Map<String, JsonElement?>? = null,
)

data class PluginVersionDto(
    val id: Int,
    @SerializedName("plugin_id") val pluginId: String,
    val version: String,
    @SerializedName("code_hash") val codeHash: String,
    val author: String,
    @SerializedName("created_at") val createdAt: String?,
)

// ---------- Logs / audit ----------
data class LogEntryDto(
    val level: String,
    val source: String,
    val message: String,
    @SerializedName("created_at") val createdAt: String?,
)

data class LogsResponse(val live: List<LogEntryDto>, val persisted: List<LogEntryDto>)

data class PluginLogsResponse(
    val live: List<LogEntryDto>,
    val persisted: List<LogEntryDto>,
    @SerializedName("last_error") val lastError: String = "",
)

data class AuditDto(
    val id: Int,
    val user: String,
    val action: String,
    val target: String,
    val details: String,
    val ip: String,
    @SerializedName("created_at") val createdAt: String,
)

// ---------- Config / backup ----------
data class ConfigResponse(val values: Map<String, String>)
data class ConfigUpdateDto(val values: Map<String, String>)

data class BackupDto(
    val name: String,
    val path: String,
    @SerializedName("size_bytes") val sizeBytes: Long,
    @SerializedName("created_at") val createdAt: String,
)

// ---------- LLM ----------
data class LlmProviderDto(
    val id: String,
    val name: String,
    val kind: String,
    @SerializedName("base_url") val baseUrl: String,
    val model: String,
    val enabled: Boolean,
    @SerializedName("api_key_set") val apiKeySet: Boolean,
)

data class LlmProviderCreateDto(
    val name: String,
    val kind: String,
    @SerializedName("base_url") val baseUrl: String = "",
    @SerializedName("api_key") val apiKey: String = "",
    val model: String = "",
    val enabled: Boolean = true,
)

data class LlmProfileDto(
    @SerializedName("system_prompt") val systemPrompt: String = "",
    val temperature: Double = 0.7,
    @SerializedName("top_p") val topP: Double = 0.9,
    @SerializedName("max_tokens") val maxTokens: Int = 512,
    val seed: Long? = null,
    @SerializedName("timeout_sec") val timeoutSec: Int = 120,
    @SerializedName("context_chars") val contextChars: Int = 4000,
)

data class LlmBindingDto(
    val id: String,
    val scope: String,
    val pattern: String,
    val enabled: Boolean,
)

data class LlmBindingCreateDto(
    val scope: String,
    val pattern: String = "",
    val enabled: Boolean = true,
)

data class LlmChatRequestDto(val message: String)
data class LlmChatResponseDto(val reply: String)
data class LlmModelsResponse(val models: List<String> = emptyList())
data class LlmTestResponse(
    val ok: Boolean = false,
    val models: List<String> = emptyList(),
    val message: String = "",
)

data class LlmStatusDto(
    val configured: Boolean,
    val provider: LlmProviderDto? = null,
    val bindings: List<LlmBindingDto> = emptyList(),
    @SerializedName("last_error") val lastError: String = "",
)

// ---------- LLM on-device jobs (agent = this phone) ----------
data class LlmJobDto(
    val id: Int,
    @SerializedName("chat_id") val chatId: String,
    val message: String,
    @SerializedName("system_prompt") val systemPrompt: String,
    val temperature: Double,
    @SerializedName("top_p") val topP: Double,
    @SerializedName("max_tokens") val maxTokens: Int,
    val status: String,
    val reply: String = "",
    val error: String = "",
    @SerializedName("claimed_by") val claimedBy: String = "",
    @SerializedName("created_at") val createdAt: String? = null,
)

data class LlmClaimResponse(val job: LlmJobDto? = null)
data class LlmJobResultDto(val reply: String)
data class LlmJobErrorDto(val error: String)

data class LlmAgentStatusDto(
    val online: Boolean,
    @SerializedName("last_seen_ago_sec") val lastSeenAgoSec: Int,
    val pending: Int,
    val running: Int,
)
