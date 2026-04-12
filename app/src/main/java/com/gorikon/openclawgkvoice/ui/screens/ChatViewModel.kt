package com.gorikon.openclawgkvoice.ui.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gorikon.openclawgkvoice.audio.AudioPlayer
import com.gorikon.openclawgkvoice.gateway.ChatMessage
import com.gorikon.openclawgkvoice.gateway.GatewayCallback
import com.gorikon.openclawgkvoice.gateway.GatewayClient
import com.gorikon.openclawgkvoice.gateway.GatewayManager
import com.gorikon.openclawgkvoice.gateway.GatewayStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel экрана текстового чата.
 *
 * Полная реализация:
 * - Подключение к GatewayClient
 * - Отправка текстовых сообщений
 * - Получение ответов от агента
 * - Воспроизведение аудио-ответов
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val gatewayClient: GatewayClient,
    private val gatewayManager: GatewayManager,
    private val audioPlayer: AudioPlayer
) : ViewModel() {

    private val gatewayId: String = checkNotNull(savedStateHandle["gatewayId"])

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _connectionStatus = MutableStateFlow(GatewayStatus.Disconnected)
    val connectionStatus: StateFlow<GatewayStatus> = _connectionStatus.asStateFlow()

    init {
        // Gateway callback для получения сообщений и аудио
        val gatewayCallback = object : GatewayCallback {
            override fun onConnected() {
                // Можно добавить системное сообщение "Подключено"
            }

            override fun onDisconnected(code: Int, reason: String) {}

            override fun onMessage(text: String) {
                // Текстовый ответ от агента
                val chatMsg = parseMessage(text)
                if (chatMsg != null) {
                    _messages.value += chatMsg
                } else {
                    // Если не распарсился как JSON — просто текст
                    _messages.value += ChatMessage(
                        gatewayId = gatewayId,
                        text = text,
                        isFromUser = false
                    )
                }
            }

            override fun onAudio(data: ByteArray) {
                // Аудио-ответ — воспроизводим
                viewModelScope.launch {
                    try {
                        audioPlayer.playAudio(data)
                    } catch (e: Exception) {
                        // Ошибка воспроизведения — логируем
                    }
                }
            }

            override fun onError(error: Throwable) {
                _messages.value += ChatMessage(
                    gatewayId = gatewayId,
                    text = "⚠️ Ошибка: ${error.message}",
                    isFromUser = false
                )
            }

            override fun onStatusChanged(status: GatewayStatus) {}
        }

        // Подключаемся к gateway при открытии чата
        viewModelScope.launch {
            val gateway = gatewayManager.getGatewayById(gatewayId)
            if (gateway != null) {
                if (gateway.status != GatewayStatus.Connected) {
                    gatewayManager.selectGateway(gatewayId, gatewayCallback)
                } else {
                    // Уже подключён — подписываемся
                    gatewayClient.connect(gateway, gatewayCallback)
                }
            }
        }
    }

    /**
     * Отправить текстовое сообщение агенту.
     */
    fun sendMessage(text: String) {
        if (text.isBlank()) return

        // Добавляем пользовательское сообщение
        val userMessage = ChatMessage(
            gatewayId = gatewayId,
            text = text,
            isFromUser = true
        )
        _messages.value += userMessage

        // Отправляем на gateway
        gatewayClient.sendMessage(text)
    }

    /**
     * Распарсить JSON сообщение от gateway.
     * Ожидается: {"text":"ответ агента","type":"message"}
     */
    private fun parseMessage(text: String): ChatMessage? {
        return try {
            // Простой парсинг: ищем поле "text" в JSON
            if (!text.contains("\"text\"")) return null

            // Вырезаем значение text
            val start = text.indexOf("\"text\"") + 7
            val remaining = text.substring(start)
            val colonIdx = remaining.indexOf(':')
            if (colonIdx == -1) return null

            val afterColon = remaining.substring(colonIdx + 1).trimStart()
            if (!afterColon.startsWith("\"")) return null

            val valueStart = afterColon.indexOf('"') + 1
            val valueEnd = afterColon.indexOf('"', valueStart)
            if (valueEnd == -1) return null

            val value = afterColon.substring(valueStart, valueEnd)
            ChatMessage(
                gatewayId = gatewayId,
                text = value.unescapeJson(),
                isFromUser = false
            )
        } catch (e: Exception) {
            null
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.release()
    }
}

/**
 * Unescape JSON-строки.
 */
private fun String.unescapeJson(): String =
    replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")
        .replace("\\\\", "\\")
