package com.botcontrol.admin.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState

/**
 * Правило проекта: любое удаление — только после подтверждения.
 * Экран держит `mutableStateOf<ConfirmRequest?>(null)`, кнопка «✕» кладёт
 * туда запрос, [ConfirmHost] показывает диалог и выполняет [action] по «Удалить».
 */
class ConfirmRequest(
    val title: String,
    val text: String = "",
    val action: () -> Unit,
)

@Composable
fun ConfirmHost(state: MutableState<ConfirmRequest?>) {
    val req = state.value ?: return
    AlertDialog(
        onDismissRequest = { state.value = null },
        title = { Text(req.title) },
        text = { if (req.text.isNotBlank()) Text(req.text) },
        confirmButton = {
            TextButton(onClick = {
                state.value = null
                req.action()
            }) { Text("Удалить") }
        },
        dismissButton = { TextButton(onClick = { state.value = null }) { Text("Отмена") } },
    )
}
