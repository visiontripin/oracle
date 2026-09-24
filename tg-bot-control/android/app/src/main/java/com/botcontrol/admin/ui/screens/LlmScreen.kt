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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import com.botcontrol.admin.data.remote.LlmBindingCreateDto
import com.botcontrol.admin.data.remote.LlmProfileDto
import com.botcontrol.admin.data.remote.LlmProviderCreateDto
import com.botcontrol.admin.data.remote.LlmProviderDto
import com.botcontrol.admin.ui.components.ErrorText
import com.botcontrol.admin.ui.components.SectionTitle
import kotlinx.coroutines.launch

private val KINDS = listOf("ollama", "lmstudio", "llamacpp", "vllm", "custom")
private val SCOPES = listOf("all", "command", "chat", "channel")

@Composable
fun LlmScreen(repository: BotRepository, onDevice: () -> Unit = {}) {
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf<com.botcontrol.admin.data.remote.LlmStatusDto?>(null) }
    var providers by remember { mutableStateOf<List<LlmProviderDto>>(emptyList()) }
    var bindings by remember { mutableStateOf<List<com.botcontrol.admin.data.remote.LlmBindingDto>>(emptyList()) }
    var profile by remember { mutableStateOf<LlmProfileDto?>(null) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }

    var selectedId by remember { mutableStateOf<String?>(null) }
    var formName by remember { mutableStateOf("") }
    var formKind by remember { mutableStateOf("ollama") }
    var formUrl by remember { mutableStateOf("") }
    var formKey by remember { mutableStateOf("") }
    var formModel by remember { mutableStateOf("") }

    var systemPrompt by remember { mutableStateOf("") }
    var temperature by remember { mutableStateOf(0.7f) }
    var topP by remember { mutableStateOf(0.9f) }
    var maxTokens by remember { mutableStateOf(512f) }
    var seedText by remember { mutableStateOf("") }

    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showBindingDialog by remember { mutableStateOf(false) }
    var testQuestion by remember { mutableStateOf("") }
    var testReply by remember { mutableStateOf("") }

    fun loadAll() {
        scope.launch {
            repository.llmStatus().onSuccess { status = it }
            repository.llmProviders().onSuccess { providers = it }
            repository.llmBindings().onSuccess { bindings = it }
            repository.llmProfile().onSuccess { p ->
                profile = p
                systemPrompt = p.systemPrompt
                temperature = p.temperature.toFloat()
                topP = p.topP.toFloat()
                maxTokens = p.maxTokens.toFloat()
                seedText = p.seed?.toString() ?: ""
            }
        }
    }

    LaunchedEffect(Unit) { loadAll() }

    fun selectProvider(p: LlmProviderDto?) {
        selectedId = p?.id
        formName = p?.name ?: ""
        formKind = p?.kind ?: "ollama"
        formUrl = p?.baseUrl ?: ""
        formKey = ""
        formModel = p?.model ?: ""
        models = emptyList()
    }

    fun saveProvider() {
        scope.launch {
            busy = true; error = ""; message = ""
            val body = LlmProviderCreateDto(
                name = formName.ifBlank { "Local LLM" }, kind = formKind,
                baseUrl = formUrl.trim(), apiKey = formKey, model = formModel.trim(),
            )
            val res = if (selectedId == null) repository.llmCreateProvider(body)
            else repository.llmUpdateProvider(selectedId!!, body)
            res.onSuccess { p ->
                message = "Provider saved"
                providers = providers.filterNot { it.id == p.id } + p
                selectedId = p.id
            }.onFailure { error = it.message ?: "Save failed" }
            busy = false
        }
    }

    fun testProvider() {
        val id = selectedId
        if (id == null) { error = "Save the provider first"; return }
        scope.launch {
            busy = true; error = ""; message = ""
            repository.llmTestProvider(id)
                .onSuccess { r -> models = r.models; message = r.message }
                .onFailure { error = it.message ?: "Test failed" }
            busy = false
        }
    }

    fun activateProvider() {
        val id = selectedId
        if (id == null) { error = "Save the provider first"; return }
        scope.launch {
            busy = true; error = ""; message = ""
            repository.llmActivateProvider(id)
                .onSuccess { message = "Activated"; loadAll() }
                .onFailure { error = it.message ?: "Activate failed" }
            busy = false
        }
    }

    fun deleteProvider() {
        val id = selectedId ?: return
        scope.launch {
            busy = true; error = ""; message = ""
            repository.llmDeleteProvider(id)
                .onSuccess { message = "Deleted"; selectProvider(null); loadAll() }
                .onFailure { error = it.message ?: "Delete failed" }
            busy = false
        }
    }

    fun saveProfile() {
        scope.launch {
            busy = true; error = ""; message = ""
            val body = LlmProfileDto(
                systemPrompt = systemPrompt, temperature = temperature.toDouble(),
                topP = topP.toDouble(), maxTokens = maxTokens.toInt(),
                seed = seedText.trim().takeIf { it.isNotBlank() }?.toLongOrNull(),
                timeoutSec = profile?.timeoutSec ?: 120, contextChars = profile?.contextChars ?: 4000,
            )
            repository.llmUpdateProfile(body)
                .onSuccess { profile = it; message = "Prompt & parameters saved" }
                .onFailure { error = it.message ?: "Save failed" }
            busy = false
        }
    }

    fun askTest() {
        scope.launch {
            busy = true; error = ""; testReply = ""
            repository.llmChat(testQuestion)
                .onSuccess { testReply = it.reply }
                .onFailure { error = it.message ?: "Chat failed" }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Local LLM", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = onDevice) { Text("On-device →") }
        }
        Text("Ollama · LM Studio · llama.cpp · vLLM — any OpenAI-compatible server",
            style = MaterialTheme.typography.bodySmall)
        if (error.isNotBlank()) ErrorText(error)
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)

        // ---- status ----
        Spacer(Modifier.height(8.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                val s = status
                if (s?.configured == true && s.provider != null) {
                    Text("● CONNECTED", color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleSmall)
                    Text("${s.provider.name} — ${s.provider.model.ifBlank { "model not selected" }}")
                    Text(s.provider.baseUrl, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("○ NOT CONFIGURED", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleSmall)
                }
                if (!s?.lastError.isNullOrBlank()) {
                    Text("last error: ${s.lastError}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
        }

        // ---- provider ----
        SectionTitle("Provider")
        Text("Editing: ${selectedId ?: "new provider"}", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            providers.forEach { p ->
                FilterChip(selected = selectedId == p.id, onClick = { selectProvider(p) },
                    label = { Text(p.name.take(12)) })
            }
            FilterChip(selected = selectedId == null, onClick = { selectProvider(null) },
                label = { Text("+ new") })
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(formName, { formName = it }, label = { Text("Name") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            KINDS.forEach { k ->
                FilterChip(selected = formKind == k,
                    onClick = { formKind = k },
                    label = { Text(k) })
            }
        }
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            formUrl, { formUrl = it },
            label = { Text("Base URL (blank = default for kind)") },
            placeholder = { Text("http://localhost:11434/v1") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            formKey, { formKey = it }, label = { Text("API key (optional, stays on server)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            formModel, { formModel = it }, label = { Text("Model") },
            placeholder = { Text("llama3.1:8b") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        if (models.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("Available models:", style = MaterialTheme.typography.bodySmall)
            models.forEach { m ->
                FilterChip(selected = formModel == m,
                    onClick = { formModel = m }, label = { Text(m) })
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { saveProvider() }, enabled = !busy) { Text("Save") }
            OutlinedButton(onClick = { testProvider() }, enabled = !busy) { Text("Test") }
            OutlinedButton(onClick = { activateProvider() }, enabled = !busy) { Text("Activate") }
            OutlinedButton(onClick = { deleteProvider() }, enabled = !busy && selectedId != null) {
                Text("Delete")
            }
        }

        // ---- profile ----
        SectionTitle("System prompt & parameters")
        OutlinedTextField(
            systemPrompt, { systemPrompt = it },
            label = { Text("System prompt") },
            modifier = Modifier.fillMaxWidth().height(120.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text("temperature: ${"%.2f".format(temperature)}")
        Slider(temperature, { temperature = it }, valueRange = 0f..2f, steps = 39)
        Text("top_p: ${"%.2f".format(topP)}")
        Slider(topP, { topP = it }, valueRange = 0f..1f, steps = 19)
        Text("max_tokens: ${maxTokens.toInt()}")
        Slider(maxTokens, { maxTokens = it }, valueRange = 32f..4096f)
        OutlinedTextField(
            seedText, { seedText = it }, label = { Text("Seed (optional)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { saveProfile() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Save prompt & parameters")
        }

        // ---- bindings ----
        SectionTitle("Connect to bot / channel")
        Text("When the bot gets a message that no plugin handled, the first matching enabled binding answers via the LLM.",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        bindings.forEach { b ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${b.scope}  ${b.pattern}", style = MaterialTheme.typography.titleSmall)
                    }
                    Switch(b.enabled, onCheckedChange = { on ->
                        scope.launch {
                            repository.llmUpdateBinding(b.id, LlmBindingCreateDto(b.scope, b.pattern, on))
                                .onSuccess { loadAll() }
                                .onFailure { error = it.message ?: "Update failed" }
                        }
                    })
                    TextButton(onClick = {
                        scope.launch {
                            repository.llmDeleteBinding(b.id)
                                .onSuccess { loadAll() }
                                .onFailure { error = it.message ?: "Delete failed" }
                        }
                    }) { Text("✕") }
                }
            }
        }
        OutlinedButton(onClick = { showBindingDialog = true }, modifier = Modifier.fillMaxWidth()) {
            Text("+ Add binding")
        }

        // ---- test chat ----
        SectionTitle("Test chat")
        OutlinedTextField(
            testQuestion, { testQuestion = it }, label = { Text("Message") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Button(onClick = { askTest() }, enabled = !busy && testQuestion.isNotBlank(),
            modifier = Modifier.fillMaxWidth()) { Text("Ask the model") }
        if (testReply.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Card(Modifier.fillMaxWidth()) {
                Text(testReply, Modifier.padding(12.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showBindingDialog) {
        var dialogScope by remember { mutableStateOf("all") }
        var dialogPattern by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showBindingDialog = false },
            title = { Text("Add LLM binding") },
            text = {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SCOPES.forEach { s ->
                            FilterChip(selected = dialogScope == s,
                                onClick = { dialogScope = s }, label = { Text(s) })
                        }
                    }
                    if (dialogScope != "all") {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            dialogPattern, { dialogPattern = it },
                            label = {
                                Text(when (dialogScope) {
                                    "command" -> "Command, e.g. /ask"
                                    "chat" -> "Chat ID, e.g. 123456789"
                                    else -> "Channel, e.g. @mychannel"
                                })
                            },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        repository.llmAddBinding(LlmBindingCreateDto(dialogScope, dialogPattern.trim()))
                            .onSuccess { showBindingDialog = false; loadAll() }
                            .onFailure { error = it.message ?: "Add failed" }
                    }
                }) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showBindingDialog = false }) { Text("Cancel") }
            },
        )
    }
}
