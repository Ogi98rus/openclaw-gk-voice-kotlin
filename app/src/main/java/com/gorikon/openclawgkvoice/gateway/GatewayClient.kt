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
import java.util.UUID
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
 * Жизненный цикл (протокол OpenClaw Gateway):
 * 1. connect() — открывает WebSocket
 * 2. Gateway шлёт connect.challenge с nonce
 * 3. Клиент отвечает методом "connect" с role/scopes/auth
 * 4. Gateway подтверждает hello-ok → считаем подключёнными
 * 5. Heartbeat (tick) каждые 15 секунд
 * 6. При обрыве — reconnect с exponential backoff
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

    // Состояние handshake
    private var handshakeNonce: String? = null
    private var isConnected = false  // true только после hello-ok

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 15_000L       // 15 секунд (как просит gateway)
        private const val MAX_RECONNECT_DELAY_MS = 60_000L
        private const val INITIAL_RECONNECT_DELAY_MS = 1_000L
        private const val MESSAGE_TYPE = "message"
        private const val AUDIO_TYPE = "audio"
    }

    /**
     * Подключиться к gateway'ю.
     * Открывает WebSocket и ждёт connect.challenge от сервера.
     */
    fun connect(config: GatewayConfig, callback: GatewayCallback) {
        if (isShutdown) return

        this.currentConfig = config
        this.callback = callback
        this.handshakeNonce = null
        this.isConnected = false

        callback.onStatusChanged(GatewayStatus.Connecting)

        val request = Request.Builder()
            .url(config.url)
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempts = 0
                // НЕ помечаем как Connected — ждём hello-ok после handshake
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingText(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                callback?.onAudio(bytes.toByteArray())
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                stopHeartbeat()
                isConnected = false
                callback?.onStatusChanged(GatewayStatus.Disconnected)
                callback?.onDisconnected(code, reason)
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                stopHeartbeat()
                isConnected = false
                callback?.onStatusChanged(GatewayStatus.Error)
                callback?.onError(t)
                scheduleReconnect()
            }
        })
    }

    /**
     * Отключиться от gateway'я.
     */
    fun disconnect() {
        isShutdown = true
        isConnected = false
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
     * Отправить текстовое сообщение агенту.
     */
    fun sendMessage(text: String) {
        if (!isConnected) return
        val message = """{"type":"$MESSAGE_TYPE","text":"${text.escapeJson()}"}"""
        webSocket?.send(message)
    }

    /**
     * Отправить аудио-данные (PCM 16-bit, 16kHz, mono).
     */
    fun sendAudio(data: ByteArray) {
        if (!isConnected) return
        webSocket?.send(ByteString.of(*data))
    }

    /**
     * Отправить heartbeat tick.
     */
    fun sendPing() {
        val tickMessage = """{"type":"tick"}"""
        webSocket?.send(tickMessage)
    }

    fun getCurrentConfig(): GatewayConfig? = currentConfig

    fun connectToActiveGateway(
        gatewayManager: GatewayManager,
        callback: GatewayCallback
    ) {
        val active = gatewayManager.getActiveGateway()
        if (active != null &&
            (active.status == GatewayStatus.Disconnected || active.status == GatewayStatus.Error)) {
            connect(active, callback)
        } else if (active?.status == GatewayStatus.Connected) {
            this.callback = callback
            callback.onStatusChanged(GatewayStatus.Connected)
            callback.onConnected()
        }
    }

    fun shutdown() {
        disconnect()
        scope.cancel()
    }

    // ====== Internal ======

    /**
     * Обработка входящего текстового сообщения.
     *
     * Протокол OpenClaw Gateway:
     * 1. connect.challenge → сохраняем nonce, отправляем connect
     * 2. hello-ok → считаем подключёнными, запускаем heartbeat
     * 3. event → обрабатываем как событие (message, status, и т.д.)
     * 4. tick → ответ на наш heartbeat (игнорируем)
     */
    private fun handleIncomingText(text: String) {
        val cb = this.callback ?: return

        try {
            val type = extractJsonField(text, "type")
            val event = extractJsonField(text, "event")

            when {
                // Gateway шлёт challenge — надо ответить connect
                type == "event" && event == "connect.challenge" -> {
                    handleChallenge(text)
                }

                // Gateway подтвердил подключение
                type == "res" && extractJsonField(text, "ok") == "true" -> {
                    handleHelloOk(text)
                }

                // Heartbeat tick от сервера — игнорируем
                type == "tick" || event == "tick" -> {
                    // no-op
                }

                // Обычное сообщение от агента
                type == "event" || type == "message" || type == "req" -> {
                    val messageText = extractJsonField(text, "text")
                    if (messageText != null) {
                        cb.onMessage(messageText)
                    } else {
                        // Может быть аудио-событие или другое
                        cb.onMessage(text)
                    }
                }

                else -> {
                    // Fallback — передаём как есть
                    cb.onMessage(text)
                }
            }
        } catch (e: Exception) {
            cb.onMessage(text)
        }
    }

    /**
     * Обработка connect.challenge — извлекаем nonce и отправляем connect.
     */
    private fun handleChallenge(json: String) {
        val nonce = extractJsonField(json, "nonce")
        if (nonce == null) {
            callback?.onError(IllegalStateException("No nonce in challenge"))
            return
        }

        handshakeNonce = nonce
        sendConnect(currentConfig?.apiKey ?: "", nonce)
    }

    /**
     * Отправка connect запроса после получения challenge.
     */
    private fun sendConnect(apiKey: String, nonce: String) {
        val connectId = UUID.randomUUID().toString()
        val connectMessage = """{
            "type":"req",
            "id":"$connectId",
            "method":"connect",
            "params":{
                "minProtocol":3,
                "maxProtocol":3,
                "client":{
                    "id":"gk-voice-android",
                    "version":"1.0.0",
                    "platform":"android",
                    "mode":"operator"
                },
                "role":"operator",
                "scopes":["operator.read","operator.write"],
                "auth":{"token":"$apiKey"}
            }
        }""".trimIndent().replace("\n", "").replace(" ", "")

        webSocket?.send(connectMessage)
    }

    /**
     * Обработка hello-ok — подключение установлено.
     */
    private fun handleHelloOk(json: String) {
        isConnected = true
        reconnectAttempts = 0
        callback?.onStatusChanged(GatewayStatus.Connected)
        callback?.onConnected()
        startHeartbeat()
    }

    /**
     * Извлечь значение строкового поля из JSON.
     */
    private fun extractJsonField(json: String, key: String): String? {
        val pattern = "\"${key}\"\\s*:\\s*\""
        val startIndex = json.indexOf(pattern)
        if (startIndex == -1) {
            // Пробуем без кавычек (для boolean/numbers)
            val patternNoQuote = "\"${key}\"\\s*:\\s*"
            val startIndex2 = json.indexOf(patternNoQuote)
            if (startIndex2 != -1) {
                val valueStart = startIndex2 + patternNoQuote.length
                // Читаем до запятой или закрывающей скобки
                var valueEnd = valueStart
                while (valueEnd < json.length) {
                    val ch = json[valueEnd]
                    if (ch == ',' || ch == '}' || ch == ']' || ch == ' ') break
                    valueEnd++
                }
                return json.substring(valueStart, valueEnd).trim('"', ' ')
            }
            return null
        }

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
     * Запуск heartbeat — каждые 15 секунд.
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
     * Переподключение с exponential backoff.
     */
    private fun scheduleReconnect() {
        if (isShutdown || currentConfig == null) return

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delayMs = (INITIAL_RECONNECT_DELAY_MS * (1 shl reconnectAttempts))
                .coerceAtMost(MAX_RECONNECT_DELAY_MS)

            delay(delayMs)
            reconnectAttempts++

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
