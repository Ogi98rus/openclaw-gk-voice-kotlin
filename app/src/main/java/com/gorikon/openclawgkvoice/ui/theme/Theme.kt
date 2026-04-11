package com.gorikon.openclawgkvoice.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Material3 dark color scheme для приложения.
 * Используем кастомные цвета из Color.kt.
 */
private val DarkColorScheme = darkColorScheme(
    primary = PrimaryColor,
    onPrimary = OnPrimaryColor,
    secondary = SecondaryColor,
    onSecondary = OnSecondaryColor,
    background = BackgroundColor,
    onBackground = OnBackgroundColor,
    surface = SurfaceColor,
    onSurface = OnSurfaceColor,
    error = ErrorColor,
    onError = Color(0xFFFFFFFF)
)

/**
 * Тема приложения — всегда тёмная.
 * В будущем можно добавить переключение через AppSettings.
 */
@Composable
fun OpenClawGKVoiceTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = AppTypography,
        content = content
    )
}
