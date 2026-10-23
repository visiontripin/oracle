package com.botcontrol.admin.data

import com.botcontrol.admin.data.remote.ApiClient
import com.botcontrol.admin.data.remote.LogEntryDto
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Live logs over WSS with exponential-backoff reconnect. UI collects [events]. */
class WebSocketManager(private val apiClient: ApiClient) {

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val _events = MutableStateFlow<List<LogEntryDto>>(emptyList())
    val events: StateFlow<List<LogEntryDto>> = _events

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private var socket: WebSocket? = null
    private var wantConnection = false
    private var reconnectAttempt = 0

    fun connect(baseUrl: String, token: String) {
        wantConnection = true
        reconnectAttempt = 0
        open(baseUrl, token)
    }

    fun disconnect() {
        wantConnection = false
        socket?.close(1000, "client disconnect")
        socket = null
        _connected.value = false
    }

    fun clear() {
        _events.value = emptyList()
    }

    private fun open(baseUrl: String, token: String) {
        if (!wantConnection) return
        val url = apiClient.webSocketUrl(baseUrl, token)
        val request = Request.Builder().url(url).build()
        socket = apiClient.okHttp.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connected.value = true
                reconnectAttempt = 0
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val obj = JsonParser.parseString(text).asJsonObject
                    if (obj.get("type")?.asString == "log") {
                        val entry = gson.fromJson(obj, LogEntryDto::class.java)
                        _events.value = (_events.value + entry).takeLast(500)
                    }
                } catch (_: Exception) {
                    // Ignore malformed frames — never crash the stream.
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connected.value = false
                scheduleReconnect(baseUrl, token)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connected.value = false
                if (wantConnection) scheduleReconnect(baseUrl, token)
            }
        })
    }

    private fun scheduleReconnect(baseUrl: String, token: String) {
        if (!wantConnection) return
        reconnectAttempt++
        val delayMs = minOf(30_000L, 1_000L * (1 shl minOf(reconnectAttempt, 5)))
        scope.launch {
            delay(delayMs)
            if (wantConnection) open(baseUrl, token)
        }
    }
}
