package com.gorikon.openclawgkvoice.gateway

import java.util.UUID

/**
 * Конфигурация одного gateway-подключения.
 * Каждый gateway — это отдельный OpenClaw-агент, к которому можно подключиться.
 */
data class GatewayConfig(
    val id: String = UUID.randomUUID().toString(),  // Уникальный идентификатор
    val name: String = "",                          // Отображаемое имя ("Мой Gateway")
    val url: String = "",                           // WebSocket URL (wss://gateway.example.com)
    val apiKey: String = "",                        // Токен авторизации
    val isActive: Boolean = false,                  // Является ли текущим выбранным
    val status: GatewayStatus = GatewayStatus.Disconnected
)

/**
 * Статус подключения gateway'я.
 */
enum class GatewayStatus {
    Disconnected,  // Не подключён
    Connecting,    // В процессе подключения
    Connected,     // Подключён и готов
    Error          // Ошибка подключения
}

/**
 * Сообщение чата между пользователем и агентом.
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val gatewayId: String,
    val text: String,
    val isFromUser: Boolean,    // true = сообщение пользователя, false = ответ агента
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Состояние экрана голосовой связи.
 */
data class VoiceState(
    val isRecording: Boolean = false,
    val isPlaying: Boolean = false,
    val amplitude: Float = 0f,   // Текущая амплитуда для визуализации
    val connectionStatus: GatewayStatus = GatewayStatus.Disconnected,
    val statusText: String = "Нажмите для записи"
)
