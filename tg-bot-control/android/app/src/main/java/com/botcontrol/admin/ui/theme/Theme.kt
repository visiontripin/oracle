package com.botcontrol.admin.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Палитра в стиле Telegram (тёмная):
 * фон #0E1621, панели #17212B, входящие #182533, исходящие #2B5278,
 * текст #F5F6F7, вторичный #7F91A4, акцент #5288C1.
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF5288C1),            // акцент и ссылки
    onPrimary = Color(0xFFF5F6F7),
    primaryContainer = Color(0xFF2B5278),   // исходящие сообщения
    onPrimaryContainer = Color(0xFFF5F6F7),
    secondary = Color(0xFF7F91A4),          // вторичный текст
    onSecondary = Color(0xFFF5F6F7),
    secondaryContainer = Color(0xFF17212B),
    onSecondaryContainer = Color(0xFF7F91A4),
    tertiary = Color(0xFFFBBF24),
    background = Color(0xFF0E1621),         // основной фон
    onBackground = Color(0xFFF5F6F7),       // основной текст
    surface = Color(0xFF17212B),            // хедер и панели
    onSurface = Color(0xFFF5F6F7),
    surfaceVariant = Color(0xFF182533),     // входящие сообщения
    onSurfaceVariant = Color(0xFF7F91A4),   // вторичный текст
    surfaceContainer = Color(0xFF17212B),
    surfaceContainerHigh = Color(0xFF17212B),
    surfaceContainerHighest = Color(0xFF17212B),
    error = Color(0xFFF87171),
    errorContainer = Color(0xFF3B1D23),
    onErrorContainer = Color(0xFFF5C6C6),
    outline = Color(0xFF33475A),
)

/**
 * Все стили текста явно получают основной цвет #F5F6F7 — ни один элемент
 * (заголовки, поля, пункты меню) не может остаться с тёмным текстом.
 */
private fun lightTypography(): androidx.compose.material3.Typography {
    val base = androidx.compose.material3.Typography()
    val white = Color(0xFFF5F6F7)
    return base.copy(
        displayLarge = base.displayLarge.copy(color = white),
        displayMedium = base.displayMedium.copy(color = white),
        displaySmall = base.displaySmall.copy(color = white),
        headlineLarge = base.headlineLarge.copy(color = white),
        headlineMedium = base.headlineMedium.copy(color = white),
        headlineSmall = base.headlineSmall.copy(color = white),
        titleLarge = base.titleLarge.copy(color = white),
        titleMedium = base.titleMedium.copy(color = white),
        titleSmall = base.titleSmall.copy(color = white),
        bodyLarge = base.bodyLarge.copy(color = white),
        bodyMedium = base.bodyMedium.copy(color = white),
        bodySmall = base.bodySmall.copy(color = white),
        labelLarge = base.labelLarge.copy(color = white),
        labelMedium = base.labelMedium.copy(color = white),
        labelSmall = base.labelSmall.copy(color = white),
    )
}

@Composable
fun BotControlTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = lightTypography(),
        content = content,
    )
}
