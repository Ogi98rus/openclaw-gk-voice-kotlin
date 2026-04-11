package com.gorikon.openclawgkvoice.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Цветовая палитра приложения — тёмная тема Material3.
 *
 * Основная схема:
 * - Фон: глубокий тёмно-синий (#0D1117)
 * - Primary: бирюзовый (#00E5FF) — акцент для кнопок и индикаторов
 * - Secondary: фиолетовый (#BB86FC) — для вторичных элементов
 * - Surface: чуть светлее фона (#161B22)
 */

// Primary colors
val PrimaryColor = Color(0xFF00E5FF)       // Бирюзовый — основной акцент
val PrimaryVariant = Color(0xFF00B8D4)     // Тёмный бирюзовый для вариаций
val OnPrimaryColor = Color(0xFF000000)     // Чёрный текст на бирюзовом фоне

// Secondary colors
val SecondaryColor = Color(0xFFBB86FC)     // Фиолетовый — вторичный акцент
val SecondaryVariant = Color(0xFF3700B3)   // Тёмный фиолетовый
val OnSecondaryColor = Color(0xFF000000)

// Background / Surface
val BackgroundColor = Color(0xFF0D1117)    // Глубокий тёмно-синий (фон)
val SurfaceColor = Color(0xFF161B22)       // Чуть светлее (карточки)
val OnBackgroundColor = Color(0xFFE6EDF3)  // Светлый текст на тёмном фоне
val OnSurfaceColor = Color(0xFFE6EDF3)

// Status colors
val ConnectedColor = Color(0xFF00C853)     // Зелёный — подключён
val ErrorColor = Color(0xFFFF1744)         // Красный — ошибка
val WarningColor = Color(0xFFFFAB00)       // Оранжевый — предупреждение
val DisconnectedColor = Color(0xFF757575)  // Серый — отключён

// Waveform
val WaveformColor = Color(0xFF00E5FF)      // Бирюзовый для waveform
val WaveformBgColor = Color(0xFF1C2333)    // Фон waveform
