package com.gorikon.openclawgkvoice.gateway

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Callback-интерфейс для событий WebSocket-подключения.
 * Реализуется ViewModel'ями для обработки входящих сообщений и аудио.
 */
interface GatewayCallback {
    fun onConnected()
    fun onDisconnected(code: Int, reason: String)
    fun onMessage(text: String)
    fun onAudio(data: ByteArray)
    fun onError(error: Throwable)
    fun onStatusChanged(status: GatewayStatus)
}

/**
 * WebSocket-клиент для подключения к одному OpenClaw gateway'ю.
 *
 * Жизненный цикл:
 * 1. connect() — устанавливает WebSocket-соединение
 * 2. Автоматическая авторизация через sendAuth() после подключения
 * 3. Heartbeat ping/pong каждые 30 секунд
 * 4. При обрыве — автоматический reconnect с exponential backoff
 * 5. disconnect() — полное закрытие
 */
@Singleton
class GatewayClient @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    private var webSocket: WebSocket? = null
    private var callback: GatewayCallback? = null
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private var currentConfig: GatewayConfig? = null
    private var reconnectAttempts = 0
    private var isShutdown = false

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 30_000L       // 30 секунд
        private const val MAX_RECONNECT_DELAY_MS = 60_000L       // Максимальная задержка — 60с
        private const val INITIAL_RECONNECT_DELAY_MS = 1_000L    // Начальная задержка — 1с
        private const val HEARTBEAT_TYPE = "heartbeat"
        private const val AUTH_TYPE = "auth"
        private const val MESSAGE_TYPE = "message"
        private const val AUDIO_TYPE = "audio"
    }

    /**
     * Подключиться к gateway'ю.
     * Запускает WebSocket, после подключения — автоматически отправляет авторизацию.
     */
    fun connect(config: GatewayConfig, callback: GatewayCallback) {
        if (isShutdown) return

        this.currentConfig = config
        this.callback = callback

        // Обновляем статус на "Connecting"
        callback.onStatusChanged(GatewayStatus.Connecting)

        val request = Request.Builder()
            .url(config.url)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempts = 0 // Сбрасываем счётчик при успешном подключении
                callback.onStatusChanged(GatewayStatus.Connected)
                callback.onConnected()

                // Отправляем авторизацию сразу после подключения
                sendAuth(config.apiKey)

                // Запускаем heartbeat
                startHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingText(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Бинарные данные — считаем аудио
                callback?.onAudio(bytes.toByteArray())
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                stopHeartbeat()
                callback?.onStatusChanged(GatewayStatus.Disconnected)
                callback?.onDisconnected(code, reason)
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                stopHeartbeat()
                callback?.onStatusChanged(GatewayStatus.Error)
                callback?.onError(t)
                scheduleReconnect()
            }
        })
    }

    /**
     * Отключиться от gateway'я.
     * Останавливает heartbeat, закрывает WebSocket, отменяет reconnect.
     */
    fun disconnect() {
        isShutdown = true
        stopHeartbeat()
        reconnectJob?.cancel()
        reconnectJob = null

        val updatedConfig = currentConfig?.copy(status = GatewayStatus.Disconnected)
        currentConfig = updatedConfig

        webSocket?.close(1000, "Client disconnected")
        webSocket = null
        callback?.onStatusChanged(GatewayStatus.Disconnected)
    }

    /**
     * Отправить авторизационное сообщение.
     * Формат: JSON с типом "auth" и токеном.
     */
    fun sendAuth(apiKey: String) {
        val authMessage = """{"type":"$AUTH_TYPE","token":"$apiKey"}"""
        webSocket?.send(authMessage)
    }

    /**
     * Отправить текстовое сообщение агенту.
     */
    fun sendMessage(text: String) {
        val message = """{"type":"$MESSAGE_TYPE","text":"${text.escapeJson()}"}"""
        webSocket?.send(message)
    }

    /**
     * Отправить аудио-данные (PCM 16-bit, 16kHz, mono).
     */
    fun sendAudio(data: ByteArray) {
        webSocket?.send(ByteString.of(*data))
    }

    /**
     * Отправить heartbeat ping.
     */
    fun sendPing() {
        val pingMessage = """{"type":"$HEARTBEAT_TYPE","timestamp":${System.currentTimeMillis()}}"""
        webSocket?.send(pingMessage)
    }

    /**
     * Получить текущую конфигурацию gateway'я.
     */
    fun getCurrentConfig(): GatewayConfig? = currentConfig

    /**
     * Подключиться к активному gateway через GatewayManager.
     * Если активный gateway есть и его статус — Disconnected или Error — подключаемся.
     */
    fun connectToActiveGateway(
        gatewayManager: GatewayManager,
        callback: GatewayCallback
    ) {
        val active = gatewayManager.getActiveGateway()
        if (active != null &&
            (active.status == GatewayStatus.Disconnected || active.status == GatewayStatus.Error)) {
            connect(active, callback)
        } else if (active?.status == GatewayStatus.Connected) {
            // Уже подключён — просто обновляем callback
            this.callback = callback
            callback.onStatusChanged(GatewayStatus.Connected)
            callback.onConnected()
        }
    }

    /**
     * Освободить ресурсы.
     */
    fun shutdown() {
        disconnect()
        scope.cancel()
    }

    // ====== Internal ======

    /**
     * Обработка входящего текстового сообщения.
     * Парсит JSON и вызывает соответствующий callback.
     *
     * Поддерживаемые форматы:
     * - {"type":"message","text":"..."} → callback.onMessage(text)
     * - {"type":"status","text":"..."} → callback.onMessage(status text)
     * - Любой другой JSON или plain text → callback.onMessage(text)
     * - heartbeat/pong — игнорируем
     */
    private fun handleIncomingText(text: String) {
        // Heartbeat pong — игнорируем
        if (text.contains("\"type\":\"heartbeat\"") || text.contains("\"type\":\"pong\"")) {
            return
        }

        // Пробуем распарсить как JSON и извлечь type + text
        val callback = this.callback ?: return

        try {
            val type = extractJsonField(text, "type")
            val messageText = extractJsonField(text, "text")

            when (type) {
                "message" -> {
                    // Текстовый ответ агента
                    if (messageText != null) {
                        callback.onMessage(messageText)
                    } else {
                        callback.onMessage(text)
                    }
                }
                "status" -> {
                    // Статусное сообщение — тоже передаём как текст
                    if (messageText != null) {
                        callback.onMessage(messageText)
                    } else {
                        callback.onMessage(text)
                    }
                }
                else -> {
                    // Неизвестный тип или plain text — передаём как есть
                    callback.onMessage(text)
                }
            }
        } catch (e: Exception) {
            // Если не удалось распарсить — передаём raw текст
            callback.onMessage(text)
        }
    }

    /**
     * Извлечь значение строкового поля из простого JSON.
     * Без полного парсинга — просто ищем "key":"value" паттерн.
     * Работает для flat JSON объектов.
     */
    private fun extractJsonField(json: String, key: String): String? {
        val pattern = "\"${key}\"\\s*:\\s*\""
        val startIndex = json.indexOf(pattern)
        if (startIndex == -1) return null

        val valueStart = startIndex + pattern.length
        var valueEnd = valueStart
        var escaped = false

        while (valueEnd < json.length) {
            val ch = json[valueEnd]
            if (escaped) {
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else if (ch == '"') {
                break
            }
            valueEnd++
        }

        return if (valueEnd > valueStart) {
            json.substring(valueStart, valueEnd)
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\\", "\\")
                .replace("\\\"", "\"")
        } else {
            null
        }
    }

    /**
     * Запуск heartbeat с периодическим ping каждые 30 секунд.
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                delay(HEARTBEAT_INTERVAL_MS)
                sendPing()
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /**
     * Запланировать переподключение с exponential backoff.
     * Задержка: 1с, 2с, 4с, 8с, ... (макс 60с).
     */
    private fun scheduleReconnect() {
        if (isShutdown || currentConfig == null) return

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delayMs = (INITIAL_RECONNECT_DELAY_MS * (1 shl reconnectAttempts))
                .coerceAtMost(MAX_RECONNECT_DELAY_MS)

            delay(delayMs)
            reconnectAttempts++

            // Переподключаемся
            currentConfig?.let { config ->
                connect(config, callback ?: return@let)
            }
        }
    }
}

/**
 * Утилита для экранирования спецсимволов в JSON-строках.
 */
private fun String.escapeJson(): String =
    replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
