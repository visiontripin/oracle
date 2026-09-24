package com.botcontrol.admin.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.botcontrol.admin.data.AuthStore
import com.botcontrol.admin.data.BotRepository
import com.botcontrol.admin.data.WebSocketManager
import com.botcontrol.admin.data.remote.ApiClient
import com.botcontrol.admin.data.remote.LogEntryDto
import com.botcontrol.admin.ui.components.ErrorText
import kotlinx.coroutines.launch

@Composable
fun LogsScreen(repository: BotRepository, apiClient: ApiClient, authStore: AuthStore) {
    val scope = rememberCoroutineScope()
    val ws = remember { WebSocketManager(apiClient) }
    val live by ws.events.collectAsState()
    val connected by ws.connected.collectAsState()

    var history by remember { mutableStateOf<List<LogEntryDto>>(emptyList()) }
    var level by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var liveMode by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        val base = authStore.baseUrl()
        val token = authStore.token().orEmpty()
        if (base.isNotBlank() && token.isNotBlank()) ws.connect(base, token)
        repository.logs().onSuccess { history = it.live + it.persisted }
    }
    DisposableEffect(Unit) { onDispose { ws.disconnect() } }

    fun matches(e: LogEntryDto): Boolean {
        if (level != null && e.level != level) return false
        if (search.isNotBlank() && !e.message.contains(search, ignoreCase = true) &&
            !e.source.contains(search, ignoreCase = true)
        ) return false
        return true
    }

    val items = ((if (liveMode) live + history else history).filter { matches(it) }).takeLast(300)
    LaunchedEffect(items.size) {
        if (items.isNotEmpty()) listState.animateScrollToItem(items.size - 1)
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Logs", style = MaterialTheme.typography.headlineMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (connected) "● live" else "○ offline",
                    color = if (connected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error)
                Switch(liveMode, { liveMode = it })
            }
        }
        if (error.isNotBlank()) ErrorText(error)
        OutlinedTextField(
            search, { search = it }, label = { Text("Search") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Level:", modifier = Modifier.align(Alignment.CenterVertically))
            listOf(null, "INFO", "WARNING", "ERROR").forEach { lv ->
                FilterChip(
                    selected = level == lv,
                    onClick = { level = lv },
                    label = { Text(lv ?: "ALL") },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                scope.launch {
                    repository.logs(level, search.ifBlank { null })
                        .onSuccess { history = it.live + it.persisted; error = "" }
                        .onFailure { error = it.message ?: "Load failed" }
                }
            }) { Text("Reload history") }
            OutlinedButton(onClick = { ws.clear(); history = emptyList() }) { Text("Clear") }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(items) { e ->
                val color = when (e.level) {
                    "ERROR" -> MaterialTheme.colorScheme.error
                    "WARNING" -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Text(
                    "[${e.level}] ${e.source}: ${e.message.take(300)}",
                    style = MaterialTheme.typography.bodySmall, color = color,
                )
            }
        }
    }
}
