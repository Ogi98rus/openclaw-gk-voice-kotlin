package com.gorikon.openclawgkvoice.messenger

import android.util.Log
import com.gorikon.openclawgkvoice.crypto.CryptoManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MessengerClient"

@Singleton
class MessengerClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val cryptoManager: CryptoManager
) {
    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private var currentServerUrl: String? = null
    private var currentToken: String? = null
    private var reconnectAttempts = 0
    private var isShutdown = false
    private var isConnecting = false
    private var isConnected = false

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _events = MutableSharedFlow<MessengerEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<MessengerEvent> = _events.asSharedFlow()

    private val _status = MutableSharedFlow<MessengerStatus>(replay = 1)
    val status: SharedFlow<MessengerStatus> = _status

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val MAX_RECONNECT_DELAY_MS = 60_000L
        private const val INITIAL_RECONNECT_DELAY_MS = 1_000L
    }

    init {
        scope.launch { _status.emit(MessengerStatus.Disconnected) }
    }

    fun connect(serverUrl: String, token: String) {
        if (isShutdown) { Log.w(TAG, "shutdown, ignoring"); return }
        if (isConnecting && !isConnected) { Log.w(TAG, "handshake in progress"); return }
        if (isConnected && currentServerUrl == serverUrl) { Log.w(TAG, "already connected"); return }

        Log.d(TAG, "connect(): $serverUrl")
        this.currentServerUrl = serverUrl
        this.currentToken = token

        val wsUrl = toWebSocketUrl(serverUrl)
        updateStatus(MessengerStatus.Connecting)
        isConnecting = true

        webSocket = okHttpClient.newWebSocket(Request.Builder().url(wsUrl).build(),
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket opened")
                    reconnectAttempts = 0
                    // Запрашиваем challenge для handshake
                    sendAuthChallenge()
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    Log.d(TAG, "onMessage: ${text.take(200)}")
                    handleIncomingMessage(text)
                }

                override fun onMessage(ws: WebSocket, bytes: ByteString) {
                    Log.d(TAG, "onMessage bytes: ${bytes.size}")
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "closed: $code $reason")
                    stopHeartbeat()
                    isConnected = false; isConnecting = false
                    updateStatus(MessengerStatus.Disconnected)
                    scope.launch { _events.emit(MessengerEvent.Disconnected(code, reason)) }
                    scheduleReconnect()
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "failure: ${t.message}")
                    stopHeartbeat()
                    isConnected = false; isConnecting = false
                    updateStatus(MessengerStatus.Error)
                    scope.launch { _events.emit(MessengerEvent.Error(t.message ?: "Unknown")) }
                    scheduleReconnect()
                }
            })
    }

    fun disconnect() {
        Log.d(TAG, "disconnect()")
        isConnecting = false; isConnected = false
        stopHeartbeat()
        reconnectJob?.cancel(); reconnectJob = null
        webSocket?.close(1000, "Client disconnected")
        webSocket = null
        updateStatus(MessengerStatus.Disconnected)
    }

    fun shutdown() { isShutdown = true; disconnect() }

    fun sendTextMessage(conversationId: String, text: String, serverPublicKey: ByteArray) {
        if (!isConnected) { Log.w(TAG, "not connected"); return }
        try {
            val ct = cryptoManager.sealText(text, serverPublicKey)
            val ctB64 = android.util.Base64.encodeToString(ct, android.util.Base64.NO_WRAP)
            val msg = buildJsonObject {
                put("type", JsonPrimitive("message_send"))
                put("payload", buildJsonObject {
                    put("conversationId", JsonPrimitive(conversationId))
                    put("message", buildJsonObject {
                        put("ciphertext", JsonPrimitive(ctB64))
                        put("nonce", JsonPrimitive(""))
                        put("encryptedKey", JsonPrimitive(""))
                        put("messageType", JsonPrimitive("text"))
                    })
                })
            }
            webSocket?.send(json.encodeToString(msg))
        } catch (e: Exception) {
            Log.e(TAG, "sendTextMessage error", e)
            scope.launch { _events.emit(MessengerEvent.Error("Send failed: ${e.message}")) }
        }
    }

    fun sendAudioMessage(conversationId: String, audioBytes: ByteArray, serverPublicKey: ByteArray) {
        if (!isConnected) { Log.w(TAG, "not connected"); return }
        try {
            val ct = cryptoManager.sealMessage(audioBytes, serverPublicKey)
            val ctB64 = android.util.Base64.encodeToString(ct, android.util.Base64.NO_WRAP)
            val msg = buildJsonObject {
                put("type", JsonPrimitive("message_send"))
                put("payload", buildJsonObject {
                    put("conversationId", JsonPrimitive(conversationId))
                    put("message", buildJsonObject {
                        put("ciphertext", JsonPrimitive(ctB64))
                        put("nonce", JsonPrimitive(""))
                        put("encryptedKey", JsonPrimitive(""))
                        put("messageType", JsonPrimitive("audio"))
                    })
                })
            }
            webSocket?.send(json.encodeToString(msg))
        } catch (e: Exception) {
            Log.e(TAG, "sendAudioMessage error", e)
        }
    }

    fun requestConversationList() {
        if (!isConnected) return
        webSocket?.send(json.encodeToString(buildJsonObject {
            put("type", JsonPrimitive("conversation_list"))
            put("payload", buildJsonObject {})
        }))
    }

    fun createConversation(title: String) {
        if (!isConnected) return
        webSocket?.send(json.encodeToString(buildJsonObject {
            put("type", JsonPrimitive("conversation_create"))
            put("payload", buildJsonObject { put("title", JsonPrimitive(title)) })
        }))
    }

    fun requestMessageHistory(conversationId: String) {
        if (!isConnected) return
        webSocket?.send(json.encodeToString(buildJsonObject {
            put("type", JsonPrimitive("message_history"))
            put("payload", buildJsonObject { put("conversationId", JsonPrimitive(conversationId)) })
        }))
    }

    fun deleteConversation(conversationId: String) {
        if (!isConnected) return
        webSocket?.send(json.encodeToString(buildJsonObject {
            put("type", JsonPrimitive("conversation_delete"))
            put("payload", buildJsonObject { put("id", JsonPrimitive(conversationId)) })
        }))
    }

    fun sendTyping(conversationId: String) {
        if (!isConnected) return
        webSocket?.send(json.encodeToString(buildJsonObject {
            put("type", JsonPrimitive("typing"))
            put("payload", buildJsonObject { put("conversationId", JsonPrimitive(conversationId)) })
        }))
    }

    // === Internal ===

    /**
     * Шаг 1: Запросить challenge (nonce) от сервера.
     */
    private fun sendAuthChallenge() {
        val id = java.util.UUID.randomUUID().toString()
        val msg = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("type", JsonPrimitive("auth_challenge"))
            put("payload", buildJsonObject {})
        }
        webSocket?.send(json.encodeToString(msg))
    }

    /**
     * Шаг 2: Ответить на challenge с токеном.
     */
    private fun sendAuthResponse(token: String, challenge: String) {
        val id = java.util.UUID.randomUUID().toString()
        val msg = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("type", JsonPrimitive("auth_response"))
            put("payload", buildJsonObject {
                put("token", JsonPrimitive(token))
                put("challenge", JsonPrimitive(challenge))
            })
        }
        webSocket?.send(json.encodeToString(msg))
    }

    private fun toWebSocketUrl(serverUrl: String): String {
        return when {
            serverUrl.startsWith("https://") -> serverUrl.replace("https://", "wss://") + "/ws"
            serverUrl.startsWith("http://") -> serverUrl.replace("http://", "ws://") + "/ws"
            serverUrl.startsWith("wss://") || serverUrl.startsWith("ws://") ->
                if (serverUrl.endsWith("/ws")) serverUrl else "$serverUrl/ws"
            else -> "ws://$serverUrl/ws"
        }
    }

    private fun sendAuth(_token: String) {
        // Заменено на sendAuthChallenge() / sendAuthResponse()
        // Этот метод оставлен для обратной совместимости (не используется)
    }

    private fun handleIncomingMessage(text: String) {
        try {
            val obj = json.parseToJsonElement(text).jsonObject
            val type = obj["type"]?.jsonPrimitive?.content ?: return
            val payload = obj["payload"]?.jsonObject

            when (type) {
                "auth_challenge" -> {
                    val challenge = payload?.get("challenge")?.jsonPrimitive?.content
                    if (challenge != null) {
                        Log.d(TAG, "Received challenge, sending auth_response")
                        sendAuthResponse(currentToken ?: "", challenge)
                    }
                }
                "connect_ok" -> {
                    Log.d(TAG, "connected!")
                    isConnected = true; isConnecting = false; reconnectAttempts = 0
                    updateStatus(MessengerStatus.Connected)
                    startHeartbeat()
                    scope.launch { _events.emit(MessengerEvent.Connected()) }
                }
                "conversation_list" -> {
                    val convJson = payload?.get("conversations")
                    if (convJson != null) {
                        val convs = json.decodeFromJsonElement<List<Conversation>>(convJson)
                        scope.launch { _events.emit(MessengerEvent.ConversationList(convs)) }
                    }
                }
                "conversation_create" -> {
                    // Сервер может вернуть {conversationId, ...} или полный Conversation
                    val convId = payload?.get("conversationId")?.jsonPrimitive?.content
                        ?: payload?.get("id")?.jsonPrimitive?.content
                        ?: payload?.get("conversation")?.jsonObject?.get("id")?.jsonPrimitive?.content
                    if (convId != null) {
                        val title = payload?.get("title")?.jsonPrimitive?.content ?: ""
                        val createdAt = payload?.get("createdAt")?.jsonPrimitive?.content ?: ""
                        val conv = Conversation(id = convId, title = title, createdAt = createdAt)
                        scope.launch { _events.emit(MessengerEvent.ConversationCreated(conv)) }
                        // После создания запрашиваем полный список для актуальных данных
                        requestConversationList()
                    }
                }
                "conversation_delete" -> {
                    payload?.get("id")?.jsonPrimitive?.content?.let { id ->
                        scope.launch { _events.emit(MessengerEvent.ConversationDeleted(id)) }
                    }
                }
                "conversation_title" -> {
                    val id = payload?.get("id")?.jsonPrimitive?.content
                    val title = payload?.get("title")?.jsonPrimitive?.content
                    if (id != null && title != null) {
                        scope.launch { _events.emit(MessengerEvent.ConversationTitle(id, title)) }
                    }
                }
                "message_receive" -> {
                    val convId = payload?.get("conversationId")?.jsonPrimitive?.content
                    val msgJson = payload?.get("message")
                    if (convId != null && msgJson != null) {
                        val msg = json.decodeFromJsonElement<Message>(msgJson)
                        scope.launch { _events.emit(MessengerEvent.MessageReceived(convId, msg)) }
                    }
                }
                "message_history" -> {
                    if (payload != null) {
                        val convId = payload["conversationId"]?.jsonPrimitive?.content ?: ""
                        val hasMore = payload["hasMore"]?.jsonPrimitive?.content?.toBoolean() ?: false
                        val nextCursor = payload["nextCursor"]?.jsonPrimitive?.content
                        val msgsJson = payload["messages"]
                        val msgs = if (msgsJson != null) json.decodeFromJsonElement<List<Message>>(msgsJson) else emptyList()
                        scope.launch { _events.emit(MessengerEvent.MessageHistory(convId, msgs, hasMore, nextCursor)) }
                    }
                }
                "typing" -> {
                    payload?.get("conversationId")?.jsonPrimitive?.content?.let { convId ->
                        scope.launch { _events.emit(MessengerEvent.Typing(convId)) }
                    }
                }
                "pong" -> { scope.launch { _events.emit(MessengerEvent.Pong) } }
                "error" -> {
                    val errMsg = payload?.get("message")?.jsonPrimitive?.content ?: "Unknown"
                    Log.e(TAG, "Server error: $errMsg")
                    scope.launch { _events.emit(MessengerEvent.Error(errMsg)) }
                }
                else -> Log.w(TAG, "Unknown type: $type")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (!isConnected) break
                sendPing()
            }
        }
    }

    private fun stopHeartbeat() { heartbeatJob?.cancel(); heartbeatJob = null }

    private fun sendPing() {
        webSocket?.send(json.encodeToString(buildJsonObject {
            put("type", JsonPrimitive("ping"))
            put("payload", buildJsonObject {})
        }))
    }

    private fun updateStatus(status: MessengerStatus) {
        scope.launch { _status.emit(status) }
    }

    private fun scheduleReconnect() {
        if (isShutdown || currentServerUrl == null || currentToken == null) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delayMs = (INITIAL_RECONNECT_DELAY_MS * (1 shl reconnectAttempts))
                .coerceAtMost(MAX_RECONNECT_DELAY_MS)
            Log.d(TAG, "Reconnecting in ${delayMs}ms (attempt ${reconnectAttempts + 1})")
            delay(delayMs)
            reconnectAttempts++
            currentServerUrl?.let { url ->
                currentToken?.let { token -> connect(url, token) }
            }
        }
    }
}
