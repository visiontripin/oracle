package com.botcontrol.admin.ui.screens

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.botcontrol.admin.data.BotRepository
import com.botcontrol.admin.data.remote.PluginUpdateDto
import com.botcontrol.admin.ui.components.ErrorText
import com.botcontrol.admin.ui.components.LoadingBox
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Code editor: WebView + CodeMirror (assets/editor.html) with Python/JSON
 * highlighting. Tabs: main.py / manifest.json / config.json.
 * Save -> server-side sandbox validation; drafts -> Room.
 */
class EditorBridge {
    @Volatile var pendingCode: String = ""
    @JavascriptInterface fun getInitialCode(): String = pendingCode
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun PluginEditorScreen(repository: BotRepository, pluginId: String, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    val gson = remember { Gson() }
    var tab by remember { mutableIntStateOf(0) }
    var code by remember { mutableStateOf<String?>(null) }
    var manifestText by remember { mutableStateOf("") }
    var configText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val bridge = remember { EditorBridge() }
    // Local editable copies (synced from WebView before tab switch / save)
    var codeEdit by remember { mutableStateOf("") }
    var manifestEdit by remember { mutableStateOf("") }
    var configEdit by remember { mutableStateOf("") }

    LaunchedEffect(pluginId) {
        val draft = repository.loadDraft(pluginId)
        repository.plugin(pluginId)
            .onSuccess { d ->
                codeEdit = draft?.code ?: d.code
                manifestEdit = draft?.manifest ?: gson.toJson(d.manifest)
                configEdit = draft?.config ?: gson.toJson(d.config)
                code = codeEdit
                manifestText = manifestEdit
                configText = configEdit
                bridge.pendingCode = codeEdit
                message = if (draft != null) "Draft restored (not saved yet)" else ""
                loaded = true
            }
            .onFailure { error = it.message ?: "Load failed" }
    }

    fun currentEdit(): String = when (tab) {
        0 -> codeEdit
        1 -> manifestEdit
        else -> configEdit
    }

    fun setCurrentEdit(value: String) {
        when (tab) {
            0 -> codeEdit = value
            1 -> manifestEdit = value
            else -> configEdit = value
        }
    }

    fun pullFromWebView(then: () -> Unit) {
        val wv = webView
        if (wv == null) {
            then()
            return
        }
        wv.evaluateJavascript("getCode()") { raw ->
            // evaluateJavascript returns a JSON-quoted string
            val decoded = try {
                JSONObject("{\"v\":$raw}").getString("v")
            } catch (_: Exception) {
                raw.trim('"')
            }
            setCurrentEdit(decoded)
            then()
        }
    }

    fun pushToWebView(value: String, mode: String) {
        val wv = webView ?: return
        val escaped = JSONObject.quote(value)
        wv.evaluateJavascript("setCode($escaped, '$mode')", null)
    }

    if (!loaded && error.isBlank()) return LoadingBox()

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Text("Edit: $pluginId", style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(8.dp))
        if (error.isNotBlank()) ErrorText(error)
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp))

        TabRow(selectedTabIndex = tab) {
            listOf("main.py", "manifest.json", "config.json").forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = {
                    pullFromWebView {
                        tab = i
                        val (value, mode) = when (i) {
                            0 -> codeEdit to "python"
                            1 -> manifestEdit to "json"
                            else -> configEdit to "json"
                        }
                        pushToWebView(value, mode)
                    }
                }, text = { Text(title) })
            }
        }

        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = WebViewClient()
                    addJavascriptInterface(bridge, "Android")
                    loadUrl("file:///android_asset/editor.html")
                    webView = this
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                pullFromWebView {
                    scope.launch {
                        repository.saveDraft(pluginId, codeEdit, manifestEdit, configEdit)
                        message = "Draft saved locally"
                    }
                }
            }, modifier = Modifier.weight(1f)) { Text("Draft") }
            Button(onClick = {
                error = ""
                message = ""
                pullFromWebView {
                    // Local JSON pre-check for friendly errors (server re-validates).
                    try {
                        if (manifestEdit.isNotBlank()) JsonParser.parseString(manifestEdit)
                        if (configEdit.isNotBlank()) JsonParser.parseString(configEdit)
                    } catch (e: Exception) {
                        error = "Invalid JSON: ${e.message}"
                        return@pullFromWebView
                    }
                    scope.launch {
                        @Suppress("UNCHECKED_CAST")
                        val manifestMap = gson.fromJson(manifestEdit, Map::class.java)
                            as? Map<String, Any?> ?: emptyMap()
                        val configMap = gson.fromJson(configEdit, Map::class.java)
                            as? Map<String, Any?> ?: emptyMap()
                        // Re-encode through Gson to JsonElement maps
                        val manifestJson = gson.toJsonTree(manifestMap).asJsonObject
                        val configJson = gson.toJsonTree(configMap).asJsonObject
                        val body = PluginUpdateDto(
                            manifest = manifestJson.entrySet().associate { it.key to it.value },
                            code = codeEdit,
                            config = configJson.entrySet().associate { it.key to it.value },
                        )
                        repository.updatePlugin(pluginId, body)
                            .onSuccess {
                                repository.clearDraft(pluginId)
                                message = "Saved & validated by server"
                                onSaved()
                            }
                            .onFailure { error = it.message ?: "Save failed (validation?)" }
                    }
                }
            }, modifier = Modifier.weight(1f)) { Text("Save") }
        }
        // keep vars referenced (displayed via WebView)
        if (false) Text(currentEdit() + code + manifestText + configText)
    }
}
