package com.botcontrol.admin.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {
    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): Response<LoginResponse>

    @POST("auth/logout")
    suspend fun logout(): Response<MessageDto>

    @GET("status")
    suspend fun status(): Response<StatusDto>

    @POST("bot/start")
    suspend fun botStart(): Response<MessageDto>

    @POST("bot/stop")
    suspend fun botStop(): Response<MessageDto>

    @POST("bot/restart")
    suspend fun botRestart(): Response<MessageDto>

    @GET("plugins")
    suspend fun plugins(): Response<List<PluginInfoDto>>

    @POST("plugins")
    suspend fun createPlugin(@Body body: PluginCreateDto): Response<PluginDetailDto>

    @GET("plugins/{id}")
    suspend fun plugin(@Path("id") id: String): Response<PluginDetailDto>

    @PUT("plugins/{id}")
    suspend fun updatePlugin(@Path("id") id: String, @Body body: PluginUpdateDto): Response<PluginDetailDto>

    @DELETE("plugins/{id}")
    suspend fun deletePlugin(@Path("id") id: String): Response<MessageDto>

    @POST("plugins/{id}/enable")
    suspend fun enablePlugin(@Path("id") id: String): Response<MessageDto>

    @POST("plugins/{id}/disable")
    suspend fun disablePlugin(@Path("id") id: String): Response<MessageDto>

    @POST("plugins/{id}/reload")
    suspend fun reloadPlugin(@Path("id") id: String): Response<MessageDto>

    @GET("plugins/{id}/logs")
    suspend fun pluginLogs(
        @Path("id") id: String,
        @Query("level") level: String? = null,
        @Query("limit") limit: Int = 200,
    ): Response<PluginLogsResponse>

    @GET("plugins/{id}/versions")
    suspend fun pluginVersions(@Path("id") id: String): Response<List<PluginVersionDto>>

    @GET("logs")
    suspend fun logs(
        @Query("level") level: String? = null,
        @Query("search") search: String? = null,
        @Query("limit") limit: Int = 200,
    ): Response<LogsResponse>

    @GET("audit")
    suspend fun audit(@Query("limit") limit: Int = 200): Response<List<AuditDto>>

    @GET("config")
    suspend fun config(): Response<ConfigResponse>

    @PUT("config")
    suspend fun updateConfig(@Body body: ConfigUpdateDto): Response<ConfigResponse>

    @POST("backup/create")
    suspend fun backupCreate(@Query("label") label: String = "manual"): Response<BackupDto>

    @GET("backups")
    suspend fun backups(): Response<List<BackupDto>>

    @POST("rollback/{version}")
    suspend fun rollback(@Path("version") version: String): Response<MessageDto>

    // ---------- LLM ----------
    @GET("llm/status")
    suspend fun llmStatus(): Response<LlmStatusDto>

    @GET("llm/providers")
    suspend fun llmProviders(): Response<List<LlmProviderDto>>

    @POST("llm/providers")
    suspend fun llmCreateProvider(@Body body: LlmProviderCreateDto): Response<LlmProviderDto>

    @PUT("llm/providers/{id}")
    suspend fun llmUpdateProvider(@Path("id") id: String, @Body body: LlmProviderCreateDto): Response<LlmProviderDto>

    @DELETE("llm/providers/{id}")
    suspend fun llmDeleteProvider(@Path("id") id: String): Response<MessageDto>

    @POST("llm/providers/{id}/activate")
    suspend fun llmActivateProvider(@Path("id") id: String): Response<MessageDto>

    @GET("llm/providers/{id}/models")
    suspend fun llmModels(@Path("id") id: String): Response<LlmModelsResponse>

    @POST("llm/providers/{id}/test")
    suspend fun llmTestProvider(@Path("id") id: String): Response<LlmTestResponse>

    @GET("llm/profile")
    suspend fun llmProfile(): Response<LlmProfileDto>

    @PUT("llm/profile")
    suspend fun llmUpdateProfile(@Body body: LlmProfileDto): Response<LlmProfileDto>

    @GET("llm/bindings")
    suspend fun llmBindings(): Response<List<LlmBindingDto>>

    @POST("llm/bindings")
    suspend fun llmAddBinding(@Body body: LlmBindingCreateDto): Response<LlmBindingDto>

    @PUT("llm/bindings/{id}")
    suspend fun llmUpdateBinding(@Path("id") id: String, @Body body: LlmBindingCreateDto): Response<LlmBindingDto>

    @DELETE("llm/bindings/{id}")
    suspend fun llmDeleteBinding(@Path("id") id: String): Response<MessageDto>

    @POST("llm/chat")
    suspend fun llmChat(@Body body: LlmChatRequestDto): Response<LlmChatResponseDto>

    // ---------- LLM on-device jobs ----------
    @POST("llm/jobs/claim")
    suspend fun llmClaimJob(): Response<LlmClaimResponse>

    @POST("llm/jobs/{id}/result")
    suspend fun llmJobResult(@Path("id") id: Int, @Body body: LlmJobResultDto): Response<MessageDto>

    @POST("llm/jobs/{id}/fail")
    suspend fun llmJobFail(@Path("id") id: Int, @Body body: LlmJobErrorDto): Response<MessageDto>

    @GET("llm/jobs")
    suspend fun llmJobs(@Query("limit") limit: Int = 30): Response<List<LlmJobDto>>

    @GET("llm/agent/status")
    suspend fun llmAgentStatus(): Response<LlmAgentStatusDto>
}
