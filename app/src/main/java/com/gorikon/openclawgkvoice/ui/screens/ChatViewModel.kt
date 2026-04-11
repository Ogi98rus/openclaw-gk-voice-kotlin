package com.gorikon.openclawgkvoice.ui.screens

import androidx.lifecycle.ViewModel
import com.gorikon.openclawgkvoice.gateway.ChatMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * ViewModel экрана текстового чата.
 *
 * В реальной реализации здесь будет подключение к GatewayClient
 * через GatewayManager для отправки/получения сообщений.
 */
@HiltViewModel
class ChatViewModel @Inject constructor() : ViewModel() {

    // Сообщения чата (в полной реализации — StateFlow из репозитория)
    val messages: List<ChatMessage> = emptyList()

    /**
     * Отправить текстовое сообщение агенту.
     */
    fun sendMessage(text: String) {
        // В реальной реализации: gatewayClient.sendMessage(text)
        // Здесь — базовая заглушка для структуры
    }
}
