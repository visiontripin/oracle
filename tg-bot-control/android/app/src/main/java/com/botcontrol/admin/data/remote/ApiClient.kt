package com.botcontrol.admin.data.remote

import com.botcontrol.admin.data.AuthStore
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/** Builds Retrofit/OkHttp clients bound to the current base URL + token. */
class ApiClient(private val authStore: AuthStore) {

    @Volatile
    private var cachedBaseUrl: String? = null

    @Volatile
    private var cachedService: ApiService? = null

    val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(authInterceptor())
            .addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun authInterceptor() = Interceptor { chain ->
        val token = runBlocking { authStore.token() }
        val req = if (token.isNullOrBlank()) {
            chain.request()
        } else {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }
        chain.proceed(req)
    }

    /** Returns API bound to [baseUrl]; rebuilds Retrofit when URL changes. */
    fun service(baseUrl: String): ApiService {
        val normalized = baseUrl.trim().trimEnd('/') + "/"
        val cached = cachedService
        if (cached != null && cachedBaseUrl == normalized) return cached
        val retrofit = Retrofit.Builder()
            .baseUrl(normalized)
            .client(okHttp)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        val service = retrofit.create(ApiService::class.java)
        cachedBaseUrl = normalized
        cachedService = service
        return service
    }

    fun webSocketUrl(baseUrl: String, token: String): String {
        val http = baseUrl.trim().trimEnd('/')
        val ws = when {
            http.startsWith("https://") -> "wss://" + http.removePrefix("https://")
            http.startsWith("http://") -> "ws://" + http.removePrefix("http://")
            else -> "ws://$http"
        }
        return "$ws/logs/stream?token=$token"
    }
}
