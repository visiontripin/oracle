package com.botcontrol.admin.llm

/** Sampling parameters applied when a model is loaded. */
data class EngineParams(
    val temperature: Float = 0.8f,
    val topK: Int = 40,
    val maxTokens: Int = 1024,
    val seed: Int? = null,
)

/**
 * On-device LLM engine abstraction. Implementations run the model fully
 * locally (no cloud calls):
 *  - [MediaPipeEngine] — classic .task/.bin models (MediaPipe GenAI);
 *  - [LitertLmEngine] — modern .litertlm models (LiteRT-LM).
 */
interface LlmEngine {
    /** Human-readable engine name shown in the UI log. */
    val kind: String

    val loaded: Boolean
    val loadedModelPath: String?

    /** Loads a model file from local storage. Blocking work happens off the main thread. */
    suspend fun load(modelPath: String, params: EngineParams)

    /**
     * One-shot generation. [system] may be empty. [params] — параметры бота,
     * который спрашивает (модель общая на все боты, настройки — у каждого свои);
     * null = параметры, с которыми модель загружена.
     */
    suspend fun generate(system: String, user: String, params: EngineParams? = null): String

    /** Frees model memory. Safe to call twice. */
    fun unload()
}
