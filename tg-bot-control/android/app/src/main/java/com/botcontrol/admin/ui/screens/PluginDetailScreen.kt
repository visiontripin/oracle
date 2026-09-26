package com.botcontrol.admin.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.botcontrol.admin.data.BotRepository
import com.botcontrol.admin.data.remote.PluginDetailDto
import com.botcontrol.admin.data.remote.PluginLogsResponse
import com.botcontrol.admin.data.remote.PluginVersionDto
import com.botcontrol.admin.ui.components.ErrorText
import com.botcontrol.admin.ui.components.LoadingBox
import com.botcontrol.admin.ui.components.SectionTitle
import kotlinx.coroutines.launch

@Composable
fun PluginDetailScreen(repository: BotRepository, pluginId: String, onEdit: () -> Unit) {
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<PluginDetailDto?>(null) }
    var logs by remember { mutableStateOf<PluginLogsResponse?>(null) }
    var versions by remember { mutableStateOf<List<PluginVersionDto>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            repository.plugin(pluginId)
                .onSuccess { detail = it; error = "" }
                .onFailure { error = it.message ?: "Load failed" }
            repository.pluginLogs(pluginId).onSuccess { logs = it }
            repository.pluginVersions(pluginId).onSuccess { versions = it }
        }
    }

    LaunchedEffect(pluginId) { refresh() }
    val d = detail

    if (d == null && error.isBlank()) return LoadingBox()

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text(d?.name ?: pluginId, style = MaterialTheme.typography.headlineMedium)
        Text("$pluginId  •  v${d?.version}  •  ${if (d?.enabled == true) "enabled" else "disabled"}")
        if (error.isNotBlank()) ErrorText(error)
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
        if (!d?.lastError.isNullOrBlank()) ErrorText("Last error: ${d!!.lastError}")

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    val r = if (d?.enabled == true) repository.disablePlugin(pluginId)
                    else repository.enablePlugin(pluginId)
                    r.onFailure { error = it.message ?: "Toggle failed" }
                    refresh()
                }
            }) { Text(if (d?.enabled == true) "Disable" else "Enable") }
            OutlinedButton(onClick = onEdit) { Text("Edit code") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                scope.launch {
                    repository.reloadPlugin(pluginId)
                        .onSuccess { message = it.message }
                        .onFailure { error = it.message ?: "Reload failed" }
                }
            }) { Text("Reload") }
            OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete") }
        }

        d?.let {
            SectionTitle("Description")
            Text(it.description.ifBlank { "—" })
            SectionTitle("Commands")
            if (it.commands.isEmpty()) Text("—") else it.commands.forEach { c ->
                Text("/${c.name} — ${c.description}")
            }
            SectionTitle("Triggers")
            if (it.triggers.isEmpty()) Text("—") else it.triggers.forEach { t ->
                Text("${t.type}:${t.pattern} → ${t.responseText.take(80)}")
            }
        }

        SectionTitle("Plugin logs")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                val entries = (logs?.live.orEmpty() + logs?.persisted.orEmpty()).takeLast(30)
                if (entries.isEmpty()) Text("No logs yet.")
                entries.forEach { e ->
                    Text("[${e.level}] ${e.message.take(200)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        SectionTitle("Version history (rollback)")
        if (versions.isEmpty()) Text("No snapshots yet.")
        versions.forEach { v ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("v${v.version} • ${v.author} • ${v.createdAt ?: ""}",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    scope.launch {
                        repository.rollback("pv:${v.id}")
                            .onSuccess { message = it.message; refresh() }
                            .onFailure { error = it.message ?: "Rollback failed" }
                    }
                }) { Text("Restore") }
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete plugin?") },
            text = { Text("'$pluginId' will be removed. A snapshot is kept for rollback.") },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        repository.deletePlugin(pluginId)
                            .onSuccess { confirmDelete = false }
                            .onFailure { error = it.message ?: "Delete failed"; confirmDelete = false }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
