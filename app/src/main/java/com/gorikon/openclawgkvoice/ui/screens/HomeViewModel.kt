package com.gorikon.openclawgkvoice.ui.screens

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gorikon.openclawgkvoice.auth.AuthRepository
import com.gorikon.openclawgkvoice.crypto.CryptoManager
import com.gorikon.openclawgkvoice.messenger.Conversation
import com.gorikon.openclawgkvoice.messenger.MessengerClient
import com.gorikon.openclawgkvoice.messenger.MessengerEvent
import com.gorikon.openclawgkvoice.messenger.MessengerStatus
import com.gorikon.openclawgkvoice.storage.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "HomeViewModel"

/**
 * ViewModel главного экрана — список conversations + статус подключения.
 *
 * Использует новый Messenger Server (один сервер, E2E шифрование, conversations).
 * Старая концепция "gateway-ев" полностью убрана.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val messengerClient: MessengerClient,
    private val authRepository: AuthRepository,
    private val cryptoManager: CryptoManager,
    private val serverRepository: ServerRepository
) : ViewModel() {

    // Список конверсаций
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    // Статус подключения к серверу
    private val _connectionStatus = MutableStateFlow(MessengerStatus.Disconnected)
    val connectionStatus: StateFlow<MessengerStatus> = _connectionStatus.asStateFlow()

    init {
        Log.d(TAG, "HomeViewModel init")
        collectEvents()
        collectStatus()
        attemptAutoConnect()
    }

    /**
     * Слушаем события от MessengerClient.
     */
    private fun collectEvents() {
        viewModelScope.launch {
            messengerClient.events.collect { event ->
                when (event) {
                    is MessengerEvent.Connected -> {
                        Log.d(TAG, "Connected: ${event.message}")
                        // После подключения запрашиваем список конверсаций
                        messengerClient.requestConversationList()
                    }

                    is MessengerEvent.Disconnected -> {
                        Log.d(TAG, "Disconnected: ${event.code} ${event.reason}")
                    }

                    is MessengerEvent.Error -> {
                        Log.e(TAG, "Error: ${event.message}")
                    }

                    is MessengerEvent.ConversationList -> {
                        Log.d(TAG, "Conversation list: ${event.conversations.size}")
                        _conversations.value = event.conversations
                    }

                    is MessengerEvent.ConversationCreated -> {
                        Log.d(TAG, "Conversation created: ${event.conversation.title}")
                        _conversations.update { current ->
                            current + event.conversation
                        }
                    }

                    is MessengerEvent.ConversationDeleted -> {
                        Log.d(TAG, "Conversation deleted: ${event.id}")
                        _conversations.update { current ->
                            current.filter { it.id != event.id }
                        }
                    }

                    is MessengerEvent.ConversationTitle -> {
                        Log.d(TAG, "Conversation title changed: ${event.id} -> ${event.title}")
                        _conversations.update { current ->
                            current.map { conv ->
                                if (conv.id == event.id) conv.copy(title = event.title) else conv
                            }
                        }
                    }

                    // Остальные события (MessageReceived, MessageHistory, Typing) обрабатываются
                    // в соответствующих ViewModel экрана чата
                    is MessengerEvent.MessageReceived,
                    is MessengerEvent.MessageHistory,
                    is MessengerEvent.Typing,
                    is MessengerEvent.Pong -> Unit // Игнорируем на главном экране
                }
            }
        }
    }

    /**
     * Слушаем статус подключения.
     */
    private fun collectStatus() {
        viewModelScope.launch {
            messengerClient.status.collect { status ->
                Log.d(TAG, "Connection status: $status")
                _connectionStatus.value = status
            }
        }
    }

    /**
     * Автоподключение если есть сохранённые credentials и serverUrl.
     */
    private fun attemptAutoConnect() {
        viewModelScope.launch {
            try {
                val credentials = authRepository.loadCredentials()
                val serverUrl = serverRepository.getServerUrl()

                if (credentials != null && serverUrl != null) {
                    Log.d(TAG, "Auto-connecting to $serverUrl")
                    messengerClient.connect(serverUrl, credentials.token)
                } else {
                    Log.d(TAG, "No credentials or serverUrl — not auto-connecting")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auto-connect error", e)
            }
        }
    }

    /**
     * Создать новую конверсацию.
     */
    fun createConversation(title: String) {
        if (title.isBlank()) return
        Log.d(TAG, "createConversation: $title")
        messengerClient.createConversation(title)
    }

    /**
     * Удалить конверсацию.
     */
    fun deleteConversation(conversationId: String) {
        Log.d(TAG, "deleteConversation: $conversationId")
        messengerClient.deleteConversation(conversationId)
    }

    /**
     * Выйти — очистить credentials и отключиться.
     */
    fun logout() {
        Log.d(TAG, "logout")
        viewModelScope.launch {
            messengerClient.disconnect()
            authRepository.clearCredentials()
            serverRepository.clearServerUrl()
            _conversations.value = emptyList()
        }
    }
}
