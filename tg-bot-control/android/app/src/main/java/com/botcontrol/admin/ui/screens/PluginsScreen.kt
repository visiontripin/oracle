package com.botcontrol.admin.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.botcontrol.admin.data.BotRepository
import com.botcontrol.admin.data.remote.PluginCreateDto
import com.botcontrol.admin.data.remote.PluginInfoDto
import com.botcontrol.admin.ui.components.ErrorText
import com.botcontrol.admin.ui.components.LoadingBox
import kotlinx.coroutines.launch

@Composable
fun PluginsScreen(repository: BotRepository, onOpen: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var plugins by remember { mutableStateOf<List<PluginInfoDto>?>(null) }
    var error by remember { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            repository.plugins()
                .onSuccess { plugins = it; error = "" }
                .onFailure { error = it.message ?: "Failed to load plugins" }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) { Text("+") }
        },
    ) { pad ->
        val list = plugins
        if (list == null && error.isBlank()) {
            LoadingBox()
        } else {
            Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
                Text("Plugins", style = MaterialTheme.typography.headlineMedium)
                if (error.isNotBlank()) ErrorText(error)
                Spacer(Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list.orEmpty()) { p ->
                        Card(Modifier.fillMaxWidth().clickable { onOpen(p.id) }) {
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("${p.name}  v${p.version}", style = MaterialTheme.typography.titleMedium)
                                    Text(p.id, style = MaterialTheme.typography.bodySmall)
                                    if (p.description.isNotBlank()) {
                                        Text(p.description, style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2)
                                    }
                                    if (p.lastError.isNotBlank()) {
                                        Text("Error: ${p.lastError.take(120)}",
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                Switch(
                                    checked = p.enabled,
                                    onCheckedChange = { enable ->
                                        scope.launch {
                                            if (enable) repository.enablePlugin(p.id)
                                            else repository.disablePlugin(p.id)
                                            refresh()
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreatePluginDialog(
            onDismiss = { showCreate = false },
            onCreate = { id, name, desc ->
                scope.launch {
                    repository.createPlugin(PluginCreateDto(id = id, name = name, description = desc))
                        .onSuccess { showCreate = false; refresh(); onOpen(it.id) }
                        .onFailure { error = it.message ?: "Create failed" }
                }
            },
        )
    }
}

@Composable
private fun CreatePluginDialog(onDismiss: () -> Unit, onCreate: (String, String, String) -> Unit) {
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    val idValid = Regex("^[a-z0-9_]{2,64}$").matches(id)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New plugin") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(id, { id = it }, label = { Text("ID (a-z, 0-9, _)") },
                    singleLine = true, isError = id.isNotBlank() && !idValid)
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(desc, { desc = it }, label = { Text("Description") })
                Text("A manifest + config + sandboxed main.py template will be created.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(id, name, desc) }, enabled = idValid && name.isNotBlank()) {
                Text("Create")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
