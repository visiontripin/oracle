package com.botcontrol.admin.llm

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * LiteRT-LM engine (com.google.ai.edge.litertlm:litertlm-android) — loads
 * the modern .litertlm model format (Gemma 3n, Qwen3, Phi-4 …).
 * API verified against litertlm-android 0.17.1 (javap on the AAR).
 *
 * Backend strategy: NPU-сборки (qualcomm/qcs в имени файла) требуют NPU —
 * пробуем NPU → GPU → CPU; обычные — CPU → GPU. Ошибки переводим на русский.
 */
class LitertLmEngine(context: Context) : LlmEngine {

    private val appContext = context.applicationContext
    private var engine: Engine? = null
    private var modelPath: String? = null

    /** Progress / diagnostics sink wired to the shared LLM log. */
    var logger: ((String) -> Unit)? = null

    override val kind: String get() = "LiteRT-LM"
    override val loaded: Boolean get() = engine?.isInitialized() == true
    override val loadedModelPath: String? get() = modelPath

    private fun friendly(error: String): String = when {
        error.contains("requires one of [npu]", true) ->
            "Эта сборка модели только для NPU (Qualcomm). Скачай универсальную (CPU/GPU) сборку — например из litert-community на HuggingFace."
        error.contains("TF_LITE_PREFILL_DECODE", true) ->
            "Файл не подходит для Android (похоже, web-сборка). Скачай .litertlm для Android: litert-community или google/gemma-3n-E2B-it-litert-lm."
        else -> error
    }

    override suspend fun load(modelPath: String, params: EngineParams) {
        withContext(Dispatchers.Default) {
            unload()
            val name = modelPath.substringAfterLast('/').lowercase()
            val npuFirst = "qualcomm" in name || "qcs" in name || "npu" in name
            val attempts: List<Pair<String, Backend>> = if (npuFirst) {
                listOf("NPU" to Backend.NPU(), "GPU" to Backend.GPU(), "CPU" to Backend.CPU())
            } else {
                listOf("CPU" to Backend.CPU(), "GPU" to Backend.GPU())
            }
            var lastError = ""
            for ((label, backend) in attempts) {
                try {
                    logger?.invoke("⚙️ Пробую бэкенд $label…")
                    val config = EngineConfig(
                        modelPath = modelPath,
                        backend = backend,
                        cacheDir = appContext.cacheDir.absolutePath,
                    )
                    val e = Engine(config)
                    e.initialize()
                    engine = e
                    this@LitertLmEngine.modelPath = modelPath
                    logger?.invoke("⚙️ Бэкенд $label подключён")
                    return@withContext
                } catch (t: Throwable) {
                    lastError = t.message ?: t.javaClass.simpleName
                    logger?.invoke("⚠️ Бэкенд $label не подошёл: ${lastError.take(140)}")
                }
            }
            error("Failed to create engine: ${friendly(lastError)}")
        }
    }

    // LiteRT-LM: сэмплер задаётся движком, per-call параметры здесь не применяются.
    override suspend fun generate(system: String, user: String, params: EngineParams?): String =
        withContext(Dispatchers.Default) {
            val e = engine ?: error("модель не загружена")
            val prompt = if (system.isBlank()) user.trim()
            else "${system.trim()}\n\n${user.trim()}"
            e.createConversation().use { conversation ->
                val message = conversation.sendMessage(prompt)
                // Models like Qwen3 emit their reasoning into channels — show it.
                message.channels.forEach { (name, text) ->
                    if (text.isNotBlank()) logger?.invoke("💭 $name: ${text.take(500)}")
                }
                message.toString()
            }
        }

    override fun unload() {
        try {
            engine?.close()
        } catch (_: Throwable) {
        }
        engine = null
        modelPath = null
    }
}
