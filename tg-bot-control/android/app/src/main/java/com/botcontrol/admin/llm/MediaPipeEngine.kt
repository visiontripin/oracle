package com.botcontrol.admin.llm

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MediaPipe LLM Inference implementation (com.google.mediapipe:tasks-genai).
 * Runs .task/.bin models (Gemma, Phi, Falcon, StableLM family) fully on-device.
 *
 * Docs: ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android
 * API notes (0.10.27): task-level options cover model path/max tokens/max top-K/
 * random seed; sampling like temperature and top-K is per-generation via
 * LlmInferenceSession.
 */
class MediaPipeEngine(context: Context) : LlmEngine {

    private val appContext = context.applicationContext
    private var llm: LlmInference? = null
    private var modelPath: String? = null
    private var temperature: Float = 0.8f
    private var topK: Int = 40
    private var seed: Int? = null

    override val kind: String get() = "MediaPipe"
    override val loaded: Boolean get() = llm != null
    override val loadedModelPath: String? get() = modelPath

    override suspend fun load(modelPath: String, params: EngineParams) {
        withContext(Dispatchers.Default) {
            unload()
            val builder = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(params.maxTokens)
                .setMaxTopK(params.topK)
            llm = LlmInference.createFromOptions(appContext, builder.build())
            this@MediaPipeEngine.modelPath = modelPath
            this@MediaPipeEngine.temperature = params.temperature
            this@MediaPipeEngine.topK = params.topK
            this@MediaPipeEngine.seed = params.seed
        }
    }

    override suspend fun generate(system: String, user: String): String =
        withContext(Dispatchers.Default) {
            val engine = llm ?: error("model is not loaded")
            val prompt = buildPrompt(system, user)
            val sessionBuilder = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTemperature(temperature)
                .setTopK(topK)
            seed?.let { sessionBuilder.setRandomSeed(it) }
            LlmInferenceSession.createFromOptions(engine, sessionBuilder.build()).use { session ->
                session.addQueryChunk(prompt)
                session.generateResponse()
            }
        }

    override fun unload() {
        try {
            llm?.close()
        } catch (_: Throwable) {
            // engine already released
        }
        llm = null
        modelPath = null
    }

    companion object {
        /** MediaPipe takes a flat prompt — combine system rules and user text. */
        fun buildPrompt(system: String, user: String): String =
            if (system.isBlank()) user.trim()
            else "${system.trim()}\n\nUser: ${user.trim()}\nAssistant:"
    }
}
