package com.botcontrol.admin.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Finds the admin API on the local Wi-Fi network ("Найти сервер" button).
 * Probes <each-IP-of-subnet>:8000/health and returns URLs answering {"ok":true}.
 */
object LanScanner {

    /** Reads the phone's Wi-Fi IPv4 (no location permission needed for own IP). */
    fun currentSubnet(context: android.content.Context): String? {
        val wifi = context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
        val ip = runCatching { wifi.connectionInfo.ipAddress }.getOrDefault(0)
        if (ip == 0) return null
        return "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}"
    }

    suspend fun scan(context: android.content.Context, port: Int = 8000): List<String> =
        withContext(Dispatchers.IO) {
            val prefix = currentSubnet(context) ?: "192.168.1"
            val client = OkHttpClient.Builder()
                .connectTimeout(300, TimeUnit.MILLISECONDS)
                .readTimeout(300, TimeUnit.MILLISECONDS)
                .build()
            coroutineScope {
                (1..254).map { last ->
                    async {
                        val host = "$prefix.$last"
                        runCatching {
                            client.newCall(
                                okhttp3.Request.Builder().url("http://$host:$port/health").build()
                            ).execute().use { resp ->
                                val body = resp.body?.string().orEmpty()
                                if (resp.isSuccessful && body.contains("\"ok\"")) "http://$host:$port" else null
                            }
                        }.getOrNull()
                    }
                }.awaitAll().filterNotNull()
            }
        }
}
