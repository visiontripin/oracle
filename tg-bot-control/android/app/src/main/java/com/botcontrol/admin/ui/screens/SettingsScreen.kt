package com.botcontrol.admin.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.botcontrol.admin.ui.components.ErrorText
import com.botcontrol.admin.ui.components.LoadingBox
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(repository: BotRepository) {
    val scope = rememberCoroutineScope()
    var values by remember { mutableStateOf<Map<String, String>?>(null) }
    var edits by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var error by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        repository.config().onSuccess { values = it.values; edits = it.values }
            .onFailure { error = it.message ?: "Load failed" }
    }

    if (values == null && error.isBlank()) return LoadingBox()

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Bot settings", style = MaterialTheme.typography.headlineMedium)
        Text("Safe subset only — secrets are never editable here.",
            style = MaterialTheme.typography.bodySmall)
        if (error.isNotBlank()) ErrorText(error)
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        edits.forEach { (key, value) ->
            OutlinedTextField(
                value = value,
                onValueChange = { edits = edits + (key to it) },
                label = { Text(key) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                scope.launch {
                    repository.updateConfig(edits)
                        .onSuccess { values = it.values; message = "Settings saved" }
                        .onFailure { error = it.message ?: "Save failed" }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save settings") }
    }
}
