package com.botcontrol.admin.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.botcontrol.admin.data.Anim
import com.botcontrol.admin.data.AnimSpec
import com.botcontrol.admin.data.ReplyPack
import kotlinx.coroutines.delay

/**
 * Редактор анимации «правкой одного сообщения»: эффект (чипы), подпись,
 * шаг, повторы, итог (текст или случайное из набора), свои кадры
 * (ASCII-арт / флипбук / текстовый квест) и живой предпросмотр —
 * ровно то, что увидит пользователь в Telegram.
 */
@Composable
fun AnimEditor(
    spec: AnimSpec,
    packs: List<ReplyPack>,
    onChange: (AnimSpec) -> Unit,
) {
    val preset = Anim.preset(spec.preset)
    val context = LocalContext.current
    // Фото из галереи копируются в память приложения: ссылка content:// живёт
    // недолго, а анимация должна работать и через неделю.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val added = uris.mapNotNull { copyAnimImage(context, it) }.map { Anim.photoFrame(it) }
        if (added.isNotEmpty()) onChange(spec.copy(preset = "custom", frames = spec.frames + added, mono = false))
    }
    val photo = Anim.isPhotoAnim(spec)
    Column {
        Text("Эффект", style = MaterialTheme.typography.bodySmall)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Anim.PRESETS.forEach { p ->
                FilterChip(
                    selected = spec.preset == p.id,
                    onClick = {
                        // Смена эффекта: подставляем его подпись/шаг/шрифт,
                        // если пользователь их ещё не менял.
                        val old = Anim.preset(spec.preset)
                        onChange(spec.copy(
                            preset = p.id,
                            text = if (spec.text.isBlank() || spec.text == old.defaultText) p.defaultText else spec.text,
                            intervalMs = if (spec.intervalMs == old.defaultInterval) p.defaultInterval else spec.intervalMs,
                            mono = if (p.id == "custom") spec.mono || old.id != "custom" else p.mono,
                        ))
                    },
                    label = { Text(p.title) },
                )
            }
        }
        Text(preset.hint, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))

        if (spec.preset == "custom") {
            OutlinedTextField(
                value = Anim.joinFrames(spec.frames),
                onValueChange = { onChange(spec.copy(frames = Anim.splitFrames(it))) },
                label = { Text("Кадры — между кадрами строка ${Anim.FRAME_SEP}") },
                placeholder = { Text("  🚀\n\n🌍\n${Anim.FRAME_SEP}\n\n  🚀\n🌍") },
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = if (spec.mono) FontFamily.Monospace else FontFamily.Default),
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 260.dp),
            )
            Text("Идеи: ASCII-заставка, флипбук (персонаж двигается), текстовый квест — " +
                "картинка меняется по ходу истории. {user} — имя собеседника.\n" +
                "Кадр-картинка: первая строка «photo: ссылка», ниже — подпись. " +
                "Кадр без photo: меняет только подпись под той же картинкой.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { picker.launch("image/*") }) { Text("🖼 Фото из галереи") }
                OutlinedButton(onClick = {
                    onChange(spec.copy(frames = spec.frames + Anim.photoFrame("https://", "Подпись"), mono = false))
                }) { Text("🔗 По ссылке") }
            }
            if (!photo) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = spec.mono, onCheckedChange = { onChange(spec.copy(mono = it)) })
                    Text("Моноширинный шрифт (для ASCII-арта)", style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            OutlinedTextField(
                value = spec.text,
                onValueChange = { onChange(spec.copy(text = it)) },
                label = { Text(if (spec.preset == "typewriter") "Текст, который «печатается»" else "Подпись") },
                singleLine = spec.preset != "typewriter",
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(6.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DropdownField(
                value = spec.intervalMs.coerceIn(Anim.MIN_INTERVAL, Anim.MAX_INTERVAL),
                options = (listOf(500, 600, 700, 800, 1000, 1500, 2000, 3000) + spec.intervalMs)
                    .filter { it in Anim.MIN_INTERVAL..Anim.MAX_INTERVAL }.distinct().sorted(),
                label = "Шаг",
                display = { "%.1f с".format(it / 1000.0) },
                onSelect = { onChange(spec.copy(intervalMs = it)) },
                modifier = Modifier.weight(1f),
            )
            if (spec.preset == "countdown") {
                DropdownField(
                    value = spec.count.coerceIn(1, 10),
                    options = (1..10).toList(),
                    label = "С числа",
                    onSelect = { onChange(spec.copy(count = it)) },
                    modifier = Modifier.weight(1f),
                )
            } else if (preset.cyclic || spec.preset == "custom") {
                DropdownField(
                    value = spec.loops.coerceIn(1, 5),
                    options = (1..5).toList(),
                    label = "Повторов",
                    display = { "×$it" },
                    onSelect = { onChange(spec.copy(loops = it)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        OutlinedTextField(
            value = spec.finalText,
            onValueChange = { onChange(spec.copy(finalText = it)) },
            label = { Text("Итог после анимации (необязательно)") },
            placeholder = { Text(if (spec.preset == "countdown") "🚀" else "✅ Готово, {user}!") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 140.dp),
        )
        if (packs.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            DropdownField(
                value = spec.packId.takeIf { id -> packs.any { it.id == id } }.orEmpty(),
                options = listOf("") + packs.map { it.id },
                label = "Итог — случайное из набора",
                display = { id -> if (id.isBlank()) "— нет (итог — текст выше)" else packs.firstOrNull { it.id == id }?.name ?: id },
                onSelect = { onChange(spec.copy(packId = it)) },
            )
        }
        Spacer(Modifier.height(8.dp))
        AnimPreview(spec, packs.firstOrNull { it.id == spec.packId }?.items.orEmpty())
    }
}

/** Живой предпросмотр: кадры сменяются с тем же шагом, что и в Telegram. */
@Composable
fun AnimPreview(spec: AnimSpec, packItems: List<String> = emptyList()) {
    if (Anim.isPhotoAnim(spec)) {
        PhotoPreview(spec, packItems)
        return
    }
    val frames = remember(spec) { Anim.frames(spec, "Иван") }
    val final = remember(spec, packItems) { Anim.final(spec, "Иван", packItems) }
    val all = remember(frames, final) { if (final.isNotBlank()) frames + final else frames }
    var index by remember(all) { mutableStateOf(0) }
    LaunchedEffect(all, spec.intervalMs) {
        if (all.size < 2) return@LaunchedEffect
        while (true) {
            // на итоговом кадре задерживаемся подольше, потом — сначала
            delay(if (index == all.lastIndex) 2000L else Anim.interval(spec).toLong())
            index = if (index >= all.lastIndex) 0 else index + 1
        }
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text("Предпросмотр · ${Anim.describe(spec)} · ≈${"%.1f".format(Anim.durationSec(spec))} с",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            val shown = all.getOrNull(index).orEmpty().ifBlank { "(нет кадров)" }
            val isFinal = final.isNotBlank() && index == all.lastIndex
            Text(
                shown,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = if (spec.mono && !isFinal) FontFamily.Monospace else FontFamily.Default),
                modifier = Modifier.heightIn(min = 40.dp),
            )
        }
    }
}

/** Предпросмотр фото-анимации: картинка кадра + подпись. */
@Composable
private fun PhotoPreview(spec: AnimSpec, packItems: List<String>) {
    val frames = remember(spec) { Anim.photoFrames(spec, "Иван") }
    val final = remember(spec, packItems) { Anim.final(spec, "Иван", packItems) }
    val total = frames.size + if (final.isNotBlank()) 1 else 0
    var index by remember(spec, packItems) { mutableStateOf(0) }
    LaunchedEffect(spec, packItems) {
        if (total < 2) return@LaunchedEffect
        while (true) {
            delay(if (index == total - 1) 2000L else Anim.interval(spec).toLong())
            index = if (index >= total - 1) 0 else index + 1
        }
    }
    val frame = frames.getOrNull(minOf(index, frames.lastIndex)) ?: return
    val caption = if (index >= frames.size) final else frame.caption
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text("Предпросмотр · ${Anim.describe(spec)} · ≈${"%.1f".format(Anim.durationSec(spec))} с",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            val bmp = remember(frame.src) {
                if (Anim.isLocalSrc(frame.src)) loadThumb(frame.src.removePrefix("file://").removePrefix("file:")) else null
            }
            if (bmp != null) {
                Image(bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp))
            } else {
                Text("🖼 ${if (frame.src.startsWith("http")) frame.src.take(60) else "file_id / " + frame.src.take(40)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (caption.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(caption, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Уменьшенная копия картинки для предпросмотра (без риска OutOfMemory). */
private fun loadThumb(path: String): android.graphics.Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / sample > 720 || bounds.outHeight / sample > 720) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

/** Копия картинки из галереи в files/anim/ — путь для кадра «photo: …». */
private fun copyAnimImage(context: Context, uri: Uri): String? = runCatching {
    val dir = java.io.File(context.filesDir, "anim").apply { mkdirs() }
    val ext = when (context.contentResolver.getType(uri)) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "jpg"
    }
    val file = java.io.File(dir, "img_${System.currentTimeMillis()}_${(1000..9999).random()}.$ext")
    val input = context.contentResolver.openInputStream(uri) ?: return@runCatching null
    input.use { inp -> file.outputStream().use { inp.copyTo(it) } }
    file.absolutePath
}.getOrNull()
