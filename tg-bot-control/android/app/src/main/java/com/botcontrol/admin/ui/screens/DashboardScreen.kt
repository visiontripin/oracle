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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.botcontrol.admin.data.AuthStore
import com.botcontrol.admin.data.BotRepository
import com.botcontrol.admin.data.remote.StatusDto
import com.botcontrol.admin.ui.components.ErrorText
import com.botcontrol.admin.ui.components.LoadingBox
import com.botcontrol.admin.ui.components.SectionTitle
import com.botcontrol.admin.ui.components.formatUptime
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(
    repository: BotRepository,
    authStore: AuthStore,
    onPlugins: () -> Unit,
    onLlm: () -> Unit,
    onLogs: () -> Unit,
    onAudit: () -> Unit,
    onSettings: () -> Unit,
    onBackup: () -> Unit,
    onLoggedOut: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<StatusDto?>(null) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            repository.status()
                .onSuccess { status = it; error = "" }
                .onFailure { error = it.message ?: "Failed to load status" }
        }
    }

    LaunchedEffect(Unit) { refresh() }
    val s = status

    if (s == null && error.isBlank()) return LoadingBox()

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Dashboard", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = {
                scope.launch {
                    repository.logout()
                    authStore.clearSession()
                    onLoggedOut()
                }
            }) { Text("Logout") }
        }

        if (error.isNotBlank()) ErrorText(error)

        s?.let {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (it.online) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (it.online) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                        Text(
                            if (it.online) "  ONLINE" else "  OFFLINE",
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Version: ${it.version}")
                    Text("Uptime: ${formatUptime(it.uptimeSeconds)}")
                    Text("Active plugins: ${it.activePlugins.size}")
                    it.activePlugins.forEach { p -> Text("  • $p") }
                    if (it.lastErrors.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Errors:", color = MaterialTheme.colorScheme.error)
                        it.lastErrors.take(5).forEach { e -> Text("  ! $e") }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { scope.launch { busy = true; repository.botStart(); refresh(); busy = false } },
                    enabled = !busy && !it.online, modifier = Modifier.weight(1f),
                ) { Text("Start") }
                Button(
                    onClick = { scope.launch { busy = true; repository.botStop(); refresh(); busy = false } },
                    enabled = !busy && it.online, modifier = Modifier.weight(1f),
                ) { Text("Stop") }
                OutlinedButton(
                    onClick = { scope.launch { busy = true; repository.botRestart(); refresh(); busy = false } },
                    enabled = !busy, modifier = Modifier.weight(1f),
                ) { Text("Restart") }
            }

            Spacer(Modifier.height(12.dp))
            SectionTitle("Manage")
            OutlinedButton(onClick = onPlugins, modifier = Modifier.fillMaxWidth()) { Text("Plugins") }
            OutlinedButton(onClick = onLlm, modifier = Modifier.fillMaxWidth()) { Text("Local LLM") }
            OutlinedButton(onClick = onLogs, modifier = Modifier.fillMaxWidth()) { Text("Live logs") }
            OutlinedButton(onClick = onAudit, modifier = Modifier.fillMaxWidth()) { Text("Audit log") }
            OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("Bot settings") }
            OutlinedButton(onClick = onBackup, modifier = Modifier.fillMaxWidth()) { Text("Backups & rollback") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { refresh() }, modifier = Modifier.fillMaxWidth()) { Text("Refresh status") }

            if (it.recentEvents.isNotEmpty()) {
                SectionTitle("Recent events")
                it.recentEvents.take(10).forEach { ev ->
                    Text(
                        "• ${ev["level"]?.asString} ${ev["source"]?.asString}: " +
                            "${ev["message"]?.asString}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
