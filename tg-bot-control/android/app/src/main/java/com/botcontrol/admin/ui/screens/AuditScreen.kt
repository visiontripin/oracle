package com.botcontrol.admin.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import com.botcontrol.admin.data.remote.AuditDto
import com.botcontrol.admin.ui.components.ErrorText
import com.botcontrol.admin.ui.components.LoadingBox
import kotlinx.coroutines.launch

@Composable
fun AuditScreen(repository: BotRepository) {
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<AuditDto>?>(null) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        scope.launch {
            repository.audit()
                .onSuccess { entries = it; error = "" }
                .onFailure { error = it.message ?: "Load failed" }
        }
    }

    val list = entries
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Admin audit log", style = MaterialTheme.typography.headlineMedium)
        if (error.isNotBlank()) ErrorText(error)
        if (list == null && error.isBlank()) {
            LoadingBox()
        } else {
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list.orEmpty()) { a ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${a.action}  •  ${a.user}", style = MaterialTheme.typography.titleSmall)
                            if (a.target.isNotBlank()) Text("target: ${a.target}",
                                style = MaterialTheme.typography.bodySmall)
                            if (a.details.isNotBlank()) Text(a.details.take(300),
                                style = MaterialTheme.typography.bodySmall)
                            Text("${a.createdAt}  •  ${a.ip}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
