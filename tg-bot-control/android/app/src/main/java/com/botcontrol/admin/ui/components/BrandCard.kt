package com.botcontrol.admin.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.botcontrol.admin.R
import com.botcontrol.admin.data.Brand

/** Логотип BotControl (res/drawable/ic_logo.xml). */
@Composable
fun BrandLogo(size: Dp = 48.dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_logo),
        contentDescription = "Логотип BotControl",
        modifier = modifier.size(size),
    )
}

/** Открыть ссылку в браузере / Telegram; без падения, если открыть нечем. */
fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Нет приложения, чтобы открыть ссылку: $url", Toast.LENGTH_LONG).show()
    }
}

private fun appVersion(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
}.getOrDefault("")

/**
 * Карточка проекта: логотип, версия и кнопки «⭐ GitHub», «⬇️ Релизы»,
 * «🤖 Демо-бот» (презентация всех функций в Telegram).
 */
@Composable
fun ProjectLinksCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandLogo(40.dp)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text("BotControl — открытый проект", style = MaterialTheme.typography.titleSmall)
                    val v = appVersion(context)
                    Text(
                        (if (v.isNotBlank()) "Версия $v · " else "") + "исходники и APK на GitHub",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { openLink(context, Brand.GITHUB_URL) }, modifier = Modifier.weight(1f)) {
                    Text("⭐ GitHub")
                }
                OutlinedButton(onClick = { openLink(context, Brand.RELEASES_URL) }, modifier = Modifier.weight(1f)) {
                    Text("⬇️ Релизы")
                }
            }
            OutlinedButton(onClick = { openLink(context, Brand.DEMO_BOT_URL) }, modifier = Modifier.fillMaxWidth()) {
                Text("🤖 Презентация в Telegram: @${Brand.DEMO_BOT}")
            }
        }
    }
}
