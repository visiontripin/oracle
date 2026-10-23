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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.botcontrol.admin.data.remote.BackupDto
import com.botcontrol.admin.ui.components.ErrorText
import com.botcontrol.admin.ui.components.LoadingBox
import kotlinx.coroutines.launch

@Composable
fun BackupScreen(repository: BotRepository) {
    val scope = rememberCoroutineScope()
    var backups by remember { mutableStateOf<List<BackupDto>?>(null) }
    var error by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    fun refresh() {
        scope.launch {
            repository.backups()
                .onSuccess { backups = it; error = "" }
                .onFailure { error = it.message ?: "Load failed (admin only?)" }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Backups & rollback", style = MaterialTheme.typography.headlineMedium)
        if (error.isNotBlank()) ErrorText(error)
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            scope.launch {
                repository.backupCreate("android")
                    .onSuccess { message = "Backup created: ${it.name}"; refresh() }
                    .onFailure { error = it.message ?: "Backup failed" }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Create backup now") }
        Spacer(Modifier.height(12.dp))
        val list = backups
        if (list == null && error.isBlank()) {
            LoadingBox()
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list.orEmpty()) { b ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(b.name, style = MaterialTheme.typography.titleSmall)
                                Text("${b.sizeBytes / 1024} KiB • ${b.createdAt}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(onClick = {
                                scope.launch {
                                    repository.rollback(b.name)
                                        .onSuccess { message = it.message }
                                        .onFailure { error = it.message ?: "Rollback failed" }
                                }
                            }) { Text("Rollback") }
                        }
                    }
                }
            }
        }
    }
}
