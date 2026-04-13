package com.gorikon.openclawgkvoice.messenger

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Модели данных для Messenger Server протокола.
 */

// ========== REST API модели ==========

@Serializable
data class PairingCodeResponse(
    val code: String,
    val qrData: String,
    val expiresAt: String
)

@Serializable
data class PairRequest(
    val code: String,
    val publicKey: String,
    val deviceName: String
)

@Serializable
data class AuthResponse(
    val token: String,
    val userId: String,
    val deviceId: String,
    val serverPublicKey: String  // Base64-encoded X25519 public key
)

@Serializable
data class RegisterRequest(
    val username: String,
    val password: String,
    val publicKey: String,
    val deviceName: String
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    val publicKey: String,
    val deviceName: String
)

@Serializable
data class ServerPublicKeyResponse(
    val publicKey: String  // Base64-encoded
)

// ========== WebSocket сообщения ==========

/**
 * Базовое WebSocket сообщение от/к серверу.
 */
@Serializable
data class WsMessage(
    val type: String,
    val payload: JsonObject? = null
)

/**
 * Сообщение авторизации при подключении к WebSocket.
 */
@Serializable
data class AuthMessage(
    val type: String = "auth",
    val payload: AuthPayload
)

@Serializable
data class AuthPayload(
    val token: String
)

// ========== Модели чата ==========

/**
 * Конверсация (диалог).
 */
@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val createdAt: String
)

/**
 * Сообщение в конверсации.
 */
@Serializable
data class Message(
    val id: String = "",
    val role: String = "",         // "user" | "assistant"
    val ciphertext: String = "",   // Base64 зашифрованного текста
    val nonce: String = "",        // Base64 nonce (если используется)
    val encryptedKey: String = "", // Base64 encrypted key (если используется)
    val messageType: String = "",  // "text" | "audio" | ...
    val status: String = "",       // "sent" | "delivered" | "read"
    val createdAt: String = ""
)

/**
 * Расшифрованное сообщение (для UI).
 */
data class DecryptedMessage(
    val id: String,
    val role: String,          // "user" | "assistant"
    val text: String,          // Расшифрованный текст
    val messageType: String,   // "text" | "audio"
    val status: String,
    val createdAt: String
)

// ========== Входящие события ==========

/**
 * События, приходящие от сервера через WebSocket.
 */
sealed class MessengerEvent {
    data class Connected(val message: String = "") : MessengerEvent()
    data class Disconnected(val code: Int, val reason: String) : MessengerEvent()
    data class Error(val message: String) : MessengerEvent()
    data object Pong : MessengerEvent()

    // Conversations
    data class ConversationList(val conversations: List<Conversation>) : MessengerEvent()
    data class ConversationCreated(val conversation: Conversation) : MessengerEvent()
    data class ConversationDeleted(val id: String) : MessengerEvent()
    data class ConversationTitle(val id: String, val title: String) : MessengerEvent()

    // Messages
    data class MessageReceived(val conversationId: String, val message: Message) : MessengerEvent()
    data class MessageHistory(
        val conversationId: String,
        val messages: List<Message>,
        val hasMore: Boolean,
        val nextCursor: String?
    ) : MessengerEvent()

    // Typing indicator
    data class Typing(val conversationId: String) : MessengerEvent()
}

/**
 * Состояние подключения Messenger.
 */
enum class MessengerStatus {
    Disconnected,
    Connecting,
    Connected,
    Error
}
