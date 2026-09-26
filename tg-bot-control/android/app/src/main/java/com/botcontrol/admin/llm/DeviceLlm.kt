package com.botcontrol.admin.llm

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Shared on-device engine holder. Both the UI (local chat) and the
 * background services use ONE loaded model instance — loading a model
 * twice would double RAM usage for no benefit.
 *
 * Also owns the live "процесс раздумий" log shown on the AI chat screen:
 * every load step, incoming request, model thinking channel and error
 * lands here.
 */
object DeviceLlm {

    data class LogEntry(val epochSec: Long, val text: String)

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> get() = _log

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> get() = _busy

    private val _engineKind = MutableStateFlow("")
    val engineKind: StateFlow<String> get() = _engineKind

    @Volatile
    var loadedModelName: String? = null
        private set

    private var engine: LlmEngine? = null

    fun log(text: String) {
        val entry = LogEntry(System.currentTimeMillis() / 1000, text)
        synchronized(_log) {
            _log.value = (_log.value + entry).takeLast(500)
        }
    }

    fun engineKindFor(modelName: String): String =
        if (modelName.endsWith(".litertlm")) "LiteRT-LM" else "MediaPipe"

    suspend fun ensureLoaded(context: Context, modelName: String, params: EngineParams): Result<Unit> {
        val file = DeviceModelsStore(context).pathFor(modelName)
        if (engine?.loaded == true && loadedModelName == modelName &&
            engine?.loadedModelPath == file.absolutePath
        ) {
            return Result.success(Unit)
        }
        val kind = engineKindFor(modelName)
        log("📦 Модель: $modelName (${file.length() / 1048576} MiB), движок: $kind")
        return try {
            _busy.value = true
            val impl: LlmEngine = when {
                modelName.endsWith(".litertlm") ->
                    engine as? LitertLmEngine ?: LitertLmEngine(context).also { engine = it }
                else ->
                    engine as? MediaPipeEngine ?: MediaPipeEngine(context).also { engine = it }
            }
            if (impl is LitertLmEngine) impl.logger = { log(it) }
            _engineKind.value = impl.kind
            log("⏳ Загружаю модель в память (10–60 с)…")
            impl.load(file.absolutePath, params)
            loadedModelName = modelName
            log("✅ Модель готова")
            Result.success(Unit)
        } catch (e: Throwable) {
            engine?.unload()
            loadedModelName = null
            log("❌ Ошибка загрузки: ${e.message ?: e.javaClass.simpleName}")
            Result.failure(e)
        } finally {
            _busy.value = false
        }
    }

    /** Убирает блок размышлений <think>…</think> из ответа модели. */
    private fun String.stripThinking(): String {
        var s = Regex("(?s)<think>.*?</think>").replace(this, "")
        if (s.contains("<think>")) {
            // незакрытый think: берём всё после, если есть закрытие, иначе пусто
            val after = s.substringAfter("<think>")
            s = if (after.contains("</think>")) after.substringAfter("</think>") else ""
        }
        return s.trim()
    }

    suspend fun generate(system: String, user: String, params: EngineParams? = null): Result<String> {
        val impl = engine ?: run {
            log("❌ Генерация отменена: модель не загружена")
            return Result.failure(IllegalStateException("модель не загружена"))
        }
        _busy.value = true
        log("💬 Запрос: ${user.take(160)}")
        return try {
            val reply = impl.generate(system, user, params).stripThinking()
            log("✅ Ответ: ${reply.length} симв.")
            Result.success(reply)
        } catch (e: Throwable) {
            log("❌ Ошибка генерации: ${e.message ?: e.javaClass.simpleName}")
            Result.failure(e)
        } finally {
            _busy.value = false
        }
    }

    fun unload() {
        engine?.unload()
        engine = null
        loadedModelName = null
        _engineKind.value = ""
        log("🧹 Модель выгружена из памяти")
    }
}

/**
 * Non-secret engine parameters persisted in plain SharedPreferences.
 * Applied at model load time — changing them requires a reload.
 */
class DevicePrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("llm_device", Context.MODE_PRIVATE)

    var selectedModel: String
        get() = prefs.getString(KEY_MODEL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    var temperature: Float
        get() = prefs.getFloat(KEY_TEMPERATURE, 0.8f)
        set(value) = prefs.edit().putFloat(KEY_TEMPERATURE, value).apply()

    var topK: Int
        get() = prefs.getInt(KEY_TOP_K, 40)
        set(value) = prefs.edit().putInt(KEY_TOP_K, value).apply()

    var maxTokens: Int
        get() = prefs.getInt(KEY_MAX_TOKENS, 1024)
        set(value) = prefs.edit().putInt(KEY_MAX_TOKENS, value).apply()

    var seed: Int
        get() = prefs.getInt(KEY_SEED, -1)
        set(value) = prefs.edit().putInt(KEY_SEED, value).apply()

    fun toParams(): EngineParams = EngineParams(
        temperature = temperature,
        topK = topK,
        maxTokens = maxTokens,
        seed = if (seed >= 0) seed else null,
    )

    private companion object {
        const val KEY_MODEL = "selected_model"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_TOP_K = "top_k"
        const val KEY_MAX_TOKENS = "max_tokens"
        const val KEY_SEED = "seed"
    }
}

/** Downloaded model files live in the app-private filesDir/models. */
class DeviceModelsStore(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "models").apply { mkdirs() }

    data class ModelFile(val name: String, val sizeBytes: Long)

    fun list(): List<ModelFile> =
        dir.listFiles()?.filter { it.isFile }
            ?.map { ModelFile(it.name, it.length()) }
            ?.sortedBy { it.name }
            ?: emptyList()

    fun pathFor(name: String): File {
        require(name.isNotBlank() && !name.contains('/') && !name.contains('\\')) { "bad model name" }
        val f = File(dir, name)
        require(f.exists()) { "model file not found: $name" }
        return f
    }

    fun freeBytes(): Long = dir.usableSpace

    fun delete(name: String): Boolean {
        require(!name.contains('/') && !name.contains('\\')) { "bad model name" }
        return File(dir, name).delete()
    }

    /** Copies a SAF-imported Uri stream into the models dir. */
    fun importFrom(source: java.io.InputStream, targetName: String): ModelFile {
        val safe = targetName.ifBlank { "imported-${System.currentTimeMillis()}.task" }
        val dest = File(dir, safe)
        source.use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
        return ModelFile(dest.name, dest.length())
    }

    fun targetFile(name: String): File {
        require(name.isNotBlank() && !name.contains('/') && !name.contains('\\')) { "bad model name" }
        return File(dir, name)
    }
}
