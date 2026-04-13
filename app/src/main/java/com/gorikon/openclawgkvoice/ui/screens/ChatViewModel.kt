package com.gorikon.openclawgkvoice.ui.screens

import android.util.Base64
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gorikon.openclawgkvoice.audio.AudioPlayer
import com.gorikon.openclawgkvoice.auth.AuthRepository
import com.gorikon.openclawgkvoice.crypto.CryptoManager
import com.gorikon.openclawgkvoice.messenger.DecryptedMessage
import com.gorikon.openclawgkvoice.messenger.MessengerClient
import com.gorikon.openclawgkvoice.messenger.MessengerEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

private const val TAG = "ChatViewModel"

/**
 * UI-представление сообщения в чате.
 * Обёртка над DecryptedMessage + локально созданные сообщения.
 */
data class UiChatMessage(
    val id: String,
    val role: String,          // "user" | "assistant"
    val text: String,
    val messageType: String,   // "text" | "audio"
    val status: String,        // "sending" | "sent" | "delivered"
    val createdAt: String,
    val isLocal: Boolean = false  // true = локальное (ещё не подтверждено сервером)
) {
    companion object {
        /** Создать локальное пользовательское сообщение */
        fun localUser(text: String): UiChatMessage {
            return UiChatMessage(
                id = UUID.randomUUID().toString(),
                role = "user",
                text = text,
                messageType = "text",
                status = "sending",
                createdAt = "",
                isLocal = true
            )
        }

        /** Создать из расшифрованного сообщения */
        fun fromDecrypted(msg: DecryptedMessage): UiChatMessage {
            return UiChatMessage(
                id = msg.id,
                role = msg.role,
                text = msg.text,
                messageType = msg.messageType,
                status = msg.status,
                createdAt = msg.createdAt
            )
        }
    }
}

/**
 * ViewModel экрана текстового чата.
 *
 * Архитектура:
 * - Один Messenger Server (не множество gateway-ев)
 * - E2E шифрование через libsodium sealed box
 * - Conversations вместо GatewayConfig
 *
 * При получении зашифрованного сообщения:
 *   1. Получаем ciphertext от сервера
 *   2. Расшифровываем через cryptoManager.openSealedText()
 *   3. Показываем DecryptedMessage в UI
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val messengerClient: MessengerClient,
    private val authRepository: AuthRepository,
    private val cryptoManager: CryptoManager,
    private val audioPlayer: AudioPlayer
) : ViewModel() {

    private val conversationId: String = checkNotNull(savedStateHandle["conversationId"])

    // Кэш публичного ключа сервера (чтобы не грузить credentials каждый раз)
    private var serverPublicKey: ByteArray? = null

    private val _messages = MutableStateFlow<List<UiChatMessage>>(emptyList())
    val messages: StateFlow<List<UiChatMessage>> = _messages.asStateFlow()

    // ID локальных сообщений, чтобы не дублировать при получении echo от сервера
    private val localMessageIds = mutableSetOf<String>()

    init {
        Log.d(TAG, "ChatViewModel init — conversationId=$conversationId")
        loadServerPublicKey()
        collectEvents()
        requestMessageHistory()
    }

    /**
     * Загрузить публичный ключ сервера из сохранённых credentials.
     */
    private fun loadServerPublicKey() {
        try {
            val credentials = authRepository.loadCredentials()
            if (credentials != null) {
                serverPublicKey = credentials.serverPublicKey
                Log.d(TAG, "Server public key loaded (${serverPublicKey!!.size} bytes)")
            } else {
                Log.e(TAG, "No credentials found — cannot decrypt messages")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load credentials", e)
        }
    }

    /**
     * Слушаем события от MessengerClient, фильтруем по нашей conversationId.
     */
    private fun collectEvents() {
        viewModelScope.launch {
            messengerClient.events.collect { event ->
                when (event) {
                    is MessengerEvent.MessageReceived -> {
                        if (event.conversationId != conversationId) return@collect

                        val msg = event.message
                        Log.d(TAG, "Message received: id=${msg.id}, role=${msg.role}, type=${msg.messageType}")

                        // Проверяем, не дубликат ли локального сообщения
                        if (msg.id.isNotEmpty() && localMessageIds.contains(msg.id)) {
                            localMessageIds.remove(msg.id)
                            return@collect
                        }

                        // Расшифровываем и добавляем
                        decryptAndAddMessage(msg)
                    }

                    is MessengerEvent.MessageHistory -> {
                        if (event.conversationId != conversationId) return@collect

                        Log.d(TAG, "Message history: ${event.messages.size} messages, hasMore=${event.hasMore}")
                        val decrypted = mutableListOf<UiChatMessage>()
                        for (msg in event.messages) {
                            val decryptedMsg = decryptMessage(msg)
                            if (decryptedMsg != null) {
                                decrypted.add(UiChatMessage.fromDecrypted(decryptedMsg))
                            }
                        }
                        // История идёт от новых к старым — реверсим для отображения
                        _messages.value = decrypted.reversed()
                    }

                    is MessengerEvent.Connected -> {
                        // После переподключения — запрашиваем историю
                        Log.d(TAG, "Connected — requesting message history")
                        requestMessageHistory()
                    }

                    is MessengerEvent.Error -> {
                        Log.e(TAG, "Error event: ${event.message}")
                    }

                    // Остальные события не относятся к текстовому чату
                    is MessengerEvent.Disconnected,
                    is MessengerEvent.Pong,
                    is MessengerEvent.ConversationList,
                    is MessengerEvent.ConversationCreated,
                    is MessengerEvent.ConversationDeleted,
                    is MessengerEvent.ConversationTitle,
                    is MessengerEvent.Typing -> Unit
                }
            }
        }
    }

    /**
     * Запросить историю сообщений для текущей conversation.
     */
    private fun requestMessageHistory() {
        Log.d(TAG, "Requesting message history for $conversationId")
        messengerClient.requestMessageHistory(conversationId)
    }

    /**
     * Отправить текстовое сообщение.
     *
     * 1. Оптимистично добавляем локальное сообщение в UI
     * 2. Шифруем и отправляем через MessengerClient
     * 3. При получении echo — заменяем на расшифрованное
     */
    fun sendMessage(text: String) {
        if (text.isBlank()) return

        val pubKey = serverPublicKey
        if (pubKey == null) {
            Log.e(TAG, "Cannot send — no server public key")
            return
        }

        // Оптимистично показываем сообщение пользователя
        val localMsg = UiChatMessage.localUser(text)
        localMessageIds.add(localMsg.id)
        _messages.value += localMsg

        // Отправляем через MessengerClient (шифрование внутри)
        Log.d(TAG, "Sending text message to $conversationId")
        messengerClient.sendTextMessage(conversationId, text, pubKey)
    }

    /**
     * Расшифровать одно сообщение и добавить в список.
     */
    private fun decryptAndAddMessage(msg: com.gorikon.openclawgkvoice.messenger.Message) {
        val decrypted = decryptMessage(msg)
        if (decrypted != null) {
            val uiMsg = UiChatMessage.fromDecrypted(decrypted)
            _messages.value += uiMsg

            // Если это аудио-сообщение — воспроизводим
            if (decrypted.messageType == "audio") {
                playAudioMessage(decrypted)
            }
        } else {
            Log.w(TAG, "Failed to decrypt message ${msg.id}")
        }
    }

    /**
     * Расшифровать сообщение.
     */
    private fun decryptMessage(msg: com.gorikon.openclawgkvoice.messenger.Message): DecryptedMessage? {
        val pubKey = serverPublicKey
        if (pubKey == null) return null

        return try {
            val ciphertext = Base64.decode(msg.ciphertext, Base64.NO_WRAP)
            val credentials = authRepository.loadCredentials()
            if (credentials == null) {
                Log.e(TAG, "No credentials for decryption")
                return null
            }

            val plaintext = cryptoManager.openSealedText(
                ciphertext = ciphertext,
                publicKey = credentials.keyPair.publicKey,
                secretKey = credentials.keyPair.secretKey
            )

            DecryptedMessage(
                id = msg.id.ifEmpty { UUID.randomUUID().toString() },
                role = msg.role,
                text = plaintext,
                messageType = msg.messageType,
                status = msg.status,
                createdAt = msg.createdAt
            )
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed for message ${msg.id}: ${e.message}")
            null
        }
    }

    /**
     * Воспроизвести аудио-сообщение (из расшифрованного PCM).
     */
    private fun playAudioMessage(decrypted: DecryptedMessage) {
        viewModelScope.launch {
            try {
                // Декодируем base64 обратно в PCM байты
                val audioBytes = Base64.decode(decrypted.text, Base64.NO_WRAP)
                audioPlayer.playAudio(audioBytes)
            } catch (e: Exception) {
                Log.e(TAG, "Audio playback failed: ${e.message}")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.release()
    }
}
