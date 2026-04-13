package com.gorikon.openclawgkvoice.gateway

import android.util.Log
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

private const val TAG = "GatewayClient"

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
    private var isConnecting = false  // true между connect() и hello-ok

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
        if (isShutdown) {
            Log.w(TAG, "connect() called but isShutdown=true, ignoring")
            return
        }

        // Если handshake уже в процессе — не создаём дубликат
        // Проверяем isConnecting, т.к. disconnect() может обнулить webSocket
        if (isConnecting && !isConnected) {
            Log.w(TAG, "connect() called but handshake in progress (isConnecting=true), ignoring duplicate")
            return
        }

        // Если уже подключены к тому же gateway — ничего не делаем
        if (isConnected && currentConfig?.url == config.url) {
            Log.w(TAG, "connect() called but already connected to same gateway, ignoring")
            return
        }

        Log.d(TAG, "connect() called: url=${config.url}, name=${config.name}")
        
        // Если уже подключены к тому же gateway — ничего не делаем
        if (isConnected && currentConfig?.url == config.url) {
            Log.w(TAG, "connect() called but already connected to same gateway, ignoring")
            return
        }
        
        // Закрываем предыдущий WebSocket если есть
        if (webSocket != null) {
            Log.w(TAG, "connect() called with existing WebSocket, closing first")
            webSocket?.close(1000, "Reconnecting")
            webSocket = null
        }
        stopHeartbeat()
        reconnectJob?.cancel()
        reconnectJob = null
        
        this.currentConfig = config
        this.callback = callback
        this.handshakeNonce = null
        this.isConnected = false

        callback.onStatusChanged(GatewayStatus.Connecting)
        Log.d(TAG, "Status changed to Connecting")

        val request = Request.Builder()
            .url(config.url)
            .build()

        Log.d(TAG, "Opening WebSocket connection...")
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened! Code: ${response.code}")
                reconnectAttempts = 0
                // НЕ помечаем как Connected — ждём hello-ok после handshake
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "onMessage text: ${text.take(150)}")
                handleIncomingText(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d(TAG, "onMessage bytes: ${bytes.size} bytes")
                callback?.onAudio(bytes.toByteArray())
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: code=$code, reason=$reason")
                stopHeartbeat()
                isConnected = false
                callback?.onStatusChanged(GatewayStatus.Disconnected)
                callback?.onDisconnected(code, reason)
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}, response code: ${response?.code}")
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
        isConnected = false
        isConnecting = false  // Сбрасываем флаг подключения
        // НЕ ставим isShutdown = true — это блокирует reconnect!
        // isShutdown только в shutdown() когда реально выключаем клиент
        stopHeartbeat()
        reconnectJob?.cancel()
        reconnectJob = null

        val updatedConfig = currentConfig?.copy(status = GatewayStatus.Disconnected)
        currentConfig = updatedConfig

        webSocket?.close(1000, "Client disconnected")
        webSocket = null
        callback?.onStatusChanged(GatewayStatus.Disconnected)
        // НЕ обнуляем callback — он может понадобиться для обработки входящих сообщений
        // и будет обновлён при следующем connect()
    }

    /**
     * Отправить текстовое сообщение агенту.
     */
    fun sendMessage(text: String) {
        if (!isConnected) {
            Log.w(TAG, "sendMessage called but not connected")
            return
        }
        val msgId = UUID.randomUUID().toString()
        val message = """{"type":"$MESSAGE_TYPE","id":"$msgId","text":"${text.escapeJson()}"}"""
        Log.d(TAG, "sendMessage: ${text.take(50)}")
        webSocket?.send(message)
    }

    /**
     * Отправить аудио-данные (PCM 16-bit, 16kHz, mono).
     */
    fun sendAudio(data: ByteArray) {
        if (!isConnected) {
            Log.w(TAG, "sendAudio called but not connected")
            return
        }
        Log.d(TAG, "sendAudio: ${data.size} bytes")
        webSocket?.send(ByteString.of(*data))
    }

    /**
     * Отправить heartbeat tick.
     */
    fun sendPing() {
        val tickId = UUID.randomUUID().toString()
        val tickMessage = """{"id":"$tickId","type":"tick"}"""
        webSocket?.send(tickMessage)
    }

    fun getCurrentConfig(): GatewayConfig? = currentConfig

    /**
     * Обновить callback (используется при reconnect к тому же gateway).
     */
    fun updateCallback(callback: GatewayCallback) {
        this.callback = callback
    }

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
        val cb = this.callback
        if (cb == null) {
            Log.w(TAG, "handleIncomingText: callback is null! message: ${text.take(80)}")
            return
        }

        try {
            val type = extractJsonField(text, "type")
            val event = extractJsonField(text, "event")
            Log.d(TAG, "handleIncomingText: type=$type, event=$event")

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
        Log.d(TAG, "handleChallenge: received connect.challenge")
        val nonce = extractJsonField(json, "nonce")
        if (nonce == null) {
            Log.e(TAG, "No nonce in challenge!")
            callback?.onError(IllegalStateException("No nonce in challenge"))
            return
        }

        Log.d(TAG, "Got nonce: ${nonce.take(8)}..., sending connect...")
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
                    "id":"cli",
                    "version":"1.0.0",
                    "platform":"android",
                    "mode":"cli"
                },
                "role":"operator",
                "scopes":["operator.read","operator.write"],
                "auth":{"token":"${apiKey}"}
            }
        }""".trimIndent().replace("\n", "").replace(" ", "")

        Log.d(TAG, "Sending connect message, id=$connectId")
        val sent = webSocket?.send(connectMessage)
        Log.d(TAG, "Connect message sent: $sent")
    }

    /**
     * Обработка hello-ok — подключение установлено.
     */
    private fun handleHelloOk(json: String) {
        Log.d(TAG, "handleHelloOk: connection established! 🎉")
        isConnected = true
        reconnectAttempts = 0
        callback?.onStatusChanged(GatewayStatus.Connected)
        callback?.onConnected()
        startHeartbeat()
    }

    /**
     * Извлечь значение строкового поля из JSON.
     * Ищем именно КЛЮЧ ("key":) а не значение, чтобы избежать коллизий
     * когда значение одного поля совпадает с именем другого ключа.
     * Пример: {"type":"event","event":"connect.challenge"} — ищем именно "event":
     */
    private fun extractJsonField(json: String, key: String): String? {
        val keyPattern = "\"$key\":"
        var startIndex = 0

        while (true) {
            val keyIndex = json.indexOf(keyPattern, startIndex)
            if (keyIndex == -1) return null

            // Убедимся что перед "key" есть : или { — значит это именно ключ
            val beforeKey = if (keyIndex > 0) json[keyIndex - 1] else ' '
            if (beforeKey == ':' || beforeKey == '{' || beforeKey == ' ' || beforeKey == ',') {
                // Это ключ, извлекаем значение
                var pos = keyIndex + keyPattern.length
                while (pos < json.length && (json[pos] == ' ' || json[pos] == '\t')) pos++
                if (pos >= json.length) return null

                if (json[pos] == '"') {
                    // Строковое значение
                    pos++
                    val valueStart = pos
                    var escaped = false
                    while (pos < json.length) {
                        val ch = json[pos]
                        if (escaped) { escaped = false }
                        else if (ch == '\\') { escaped = true }
                        else if (ch == '"') { break }
                        pos++
                    }
                    return if (pos > valueStart) {
                        json.substring(valueStart, pos)
                            .replace("\\n", "\n")
                            .replace("\\r", "\r")
                            .replace("\\t", "\t")
                            .replace("\\\\", "\\")
                            .replace("\\\"", "\"")
                    } else null
                } else {
                    // Примитив (boolean, number)
                    val valueStart = pos
                    while (pos < json.length) {
                        val ch = json[pos]
                        if (ch == ',' || ch == '}' || ch == ']' || ch == ' ') break
                        pos++
                    }
                    return json.substring(valueStart, pos).trim()
                }
            }

            // Не ключ (значение), ищем дальше
            startIndex = keyIndex + 1
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
