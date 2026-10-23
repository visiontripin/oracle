package com.botcontrol.admin.llm

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Streaming model downloader (models are 1–4 GB — never bundled in the APK).
 * Optional Bearer token for gated HuggingFace/Kaggle direct links.
 */
class ModelDownloader(private val client: OkHttpClient = OkHttpClient()) {

    suspend fun download(
        url: String,
        dest: File,
        bearerToken: String? = null,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder().url(url).get()
            if (!bearerToken.isNullOrBlank()) builder.header("Authorization", "Bearer $bearerToken")
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("HTTP ${resp.code} — для Kaggle/HF часто нужно принять лицензию"))
                }
                val body = resp.body ?: return@withContext Result.failure(
                    IllegalStateException("empty response body"))
                val total = body.contentLength()
                dest.outputStream().use { out ->
                    val source = body.source()
                    var done = 0L
                    val buffer = okio.Buffer()
                    while (true) {
                        val read = source.read(buffer, CHUNK)
                        if (read == -1L) break
                        out.write(buffer.readByteArray())
                        done += read
                        onProgress(done, total)
                    }
                }
                Result.success(dest)
            }
        } catch (e: Throwable) {
            dest.delete()
            Result.failure(e)
        }
    }

    private companion object {
        const val CHUNK = 64L * 1024
    }
}

/**
 * Well-known models runnable on-device. License terms differ per model, so
 * the app links to the official page (accept license → get direct URL) and
 * also supports importing an already-downloaded file via SAF.
 * Formats: .task/.bin (MediaPipe) and .litertlm (LiteRT-LM — incl. Qwen3).
 * ВНИМАНИЕ: не качай сборки с «web» в имени (это web-экспорт, Android его
 * не откроет) и «qualcomm»/«qcs…» (только для NPU). Нужны обычные Android-сборки.
 */
data class CatalogEntry(
    val name: String,
    val paramsHint: String,
    val sizeHint: String,
    val infoUrl: String,
)

object ModelCatalog {
    val entries = listOf(
        CatalogEntry(
            "Gemma-3 1B IT (.task, LiteRT Community)",
            "≈2 GB RAM • лучший баланс для телефона",
            "~0.5–1 GB",
            "https://huggingface.co/litert-community",
        ),
        CatalogEntry(
            "Qwen3 0.6B (.litertlm) — поддержан",
            "≈1–2 GB RAM • работает движком LiteRT-LM",
            "~0.5 GB",
            "https://huggingface.co/litert-community",
        ),
        CatalogEntry(
            "Gemma-3n E2B (.litertlm)",
            "≈3–4 GB RAM • мультимодальная, движок LiteRT-LM",
            "~2–3 GB",
            "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm",
        ),
        CatalogEntry(
            "Phi-3 mini / Falcon / StableLM",
            "лёгкие альтернативы в формате .task",
            "~1–3 GB",
            "https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference#models",
        ),
    )
}
