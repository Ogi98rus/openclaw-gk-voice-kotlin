package com.gorikon.openclawgkvoice.ui.screens

import android.util.Base64
import android.util.Log
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.gorikon.openclawgkvoice.audio.AudioPlayer
import com.gorikon.openclawgkvoice.audio.AudioRecorder
import com.gorikon.openclawgkvoice.audio.AudioRecorderCallback
import com.gorikon.openclawgkvoice.auth.AuthRepository
import com.gorikon.openclawgkvoice.crypto.CryptoManager
import com.gorikon.openclawgkvoice.messenger.MessengerClient
import com.gorikon.openclawgkvoice.messenger.MessengerEvent
import com.gorikon.openclawgkvoice.messenger.MessengerStatus
import com.gorikon.openclawgkvoice.util.PermissionHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "VoiceViewModel"

/**
 * Состояние экрана голосовой связи (новая архитектура — Messenger Server).
 */
data class VoiceState(
    val isRecording: Boolean = false,
    val isPlaying: Boolean = false,
    val amplitude: Float = 0f,
    val connectionStatus: MessengerStatus = MessengerStatus.Disconnected,
    val statusText: String = "Нажмите для записи"
)

/**
 * ViewModel экрана голосовой связи.
 *
 * Архитектура (новая):
 * - Один Messenger Server (не множество gateway-ев)
 * - E2E шифрование через libsodium sealed box
 * - AudioRecorder записывает PCM 16kHz → аккумулируем → отправляем одним аудио-сообщением
 * - При получении аудио-ответа — расшифровываем и воспроизводим через AudioPlayer
 *
 * Отличие от старой архитектуры:
 * - Раньше: streaming audio chunks через GatewayClient.sendAudio()
 * - Теперь: аккумулируем записанные данные → sendAudioMessage() (целиком, с шифрованием)
 */
@HiltViewModel
class VoiceViewModel @Inject constructor(
    application: Application,
    savedStateHandle: SavedStateHandle,
    private val messengerClient: MessengerClient,
    private val authRepository: AuthRepository,
    private val cryptoManager: CryptoManager,
    private val audioRecorder: AudioRecorder,
    private val audioPlayer: AudioPlayer
) : AndroidViewModel(application) {

    private val conversationId: String = checkNotNull(savedStateHandle["conversationId"])

    // Кэш публичного ключа сервера
    private var serverPublicKey: ByteArray? = null

    private val _voiceState = MutableStateFlow(VoiceState())
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    // Буфер для аккумулирования аудио-чанков во время записи
    private val audioBuffer = mutableListOf<ByteArray>()

    init {
        Log.d(TAG, "VoiceViewModel init — conversationId=$conversationId")
        loadServerPublicKey()
        collectEvents()
        collectStatus()
        setupAudioRecorderCallback()
    }

    /**
     * Загрузить публичный ключ сервера из credentials.
     */
    private fun loadServerPublicKey() {
        try {
            val credentials = authRepository.loadCredentials()
            if (credentials != null) {
                serverPublicKey = credentials.serverPublicKey
                Log.d(TAG, "Server public key loaded (${serverPublicKey!!.size} bytes)")
            } else {
                Log.e(TAG, "No credentials found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load credentials", e)
        }
    }

    /**
     * Слушаем события от MessengerClient.
     */
    private fun collectEvents() {
        viewModelScope.launch {
            messengerClient.events.collect { event ->
                when (event) {
                    is MessengerEvent.MessageReceived -> {
                        if (event.conversationId != conversationId) return@collect

                        val msg = event.message
                        Log.d(TAG, "Voice: message received — type=${msg.messageType}")

                        if (msg.messageType == "audio") {
                            decryptAndPlayAudio(msg)
                        }
                    }

                    is MessengerEvent.Connected -> {
                        _voiceState.update {
                            it.copy(
                                connectionStatus = MessengerStatus.Connected,
                                statusText = "Подключено — готов к записи"
                            )
                        }
                    }

                    is MessengerEvent.Disconnected -> {
                        _voiceState.update {
                            it.copy(
                                connectionStatus = MessengerStatus.Disconnected,
                                statusText = "Отключено"
                            )
                        }
                    }

                    is MessengerEvent.Error -> {
                        _voiceState.update {
                            it.copy(
                                connectionStatus = MessengerStatus.Error,
                                statusText = "Ошибка: ${event.message}"
                            )
                        }
                    }

                    // Остальные события не релевантны для голосового экрана
                    is MessengerEvent.Pong,
                    is MessengerEvent.ConversationList,
                    is MessengerEvent.ConversationCreated,
                    is MessengerEvent.ConversationDeleted,
                    is MessengerEvent.ConversationTitle,
                    is MessengerEvent.MessageHistory,
                    is MessengerEvent.Typing -> Unit
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
                _voiceState.update {
                    it.copy(
                        connectionStatus = status,
                        statusText = when (status) {
                            MessengerStatus.Connected -> "Готов к записи"
                            MessengerStatus.Connecting -> "Подключение..."
                            MessengerStatus.Error -> "Ошибка подключения"
                            MessengerStatus.Disconnected -> "Отключено"
                        }
                    )
                }
            }
        }
    }

    /**
     * Настроить callback для AudioRecorder.
     * Аккумулируем аудио-чанки в буфер.
     */
    private fun setupAudioRecorderCallback() {
        audioRecorder.setCallback(object : AudioRecorderCallback {
            override fun onAudioChunk(data: ByteArray) {
                audioBuffer.add(data.copyOf())
            }

            override fun onAmplitudeChanged(amplitude: Float) {
                _voiceState.update { it.copy(amplitude = amplitude) }
            }

            override fun onError(error: String) {
                _voiceState.update {
                    it.copy(
                        isRecording = false,
                        statusText = "Ошибка записи: $error",
                        amplitude = 0f
                    )
                }
            }
        })
    }

    /**
     * Начать запись голоса.
     * Проверяет permission и статус подключения.
     */
    fun startRecording() {
        val context = getApplication<android.app.Application>()

        // Проверяем permission
        if (!PermissionHelper.checkAudioPermission(context)) {
            _voiceState.update { it.copy(statusText = "Требуется разрешение на запись") }
            return
        }

        // Проверяем подключение
        if (_voiceState.value.connectionStatus != MessengerStatus.Connected) {
            _voiceState.update { it.copy(statusText = "Сначала подключитесь к серверу") }
            return
        }

        // Проверяем ключ сервера
        if (serverPublicKey == null) {
            _voiceState.update { it.copy(statusText = "Не удалось загрузить ключ шифрования") }
            return
        }

        try {
            // Очищаем буфер и начинаем запись
            audioBuffer.clear()
            audioRecorder.startRecording()

            _voiceState.update {
                it.copy(
                    isRecording = true,
                    statusText = "Запись...",
                    amplitude = 0f
                )
            }

            Log.d(TAG, "Recording started")
        } catch (e: Exception) {
            _voiceState.update { it.copy(statusText = "Ошибка: ${e.message}") }
            Log.e(TAG, "startRecording error", e)
        }
    }

    /**
     * Остановить запись и отправить аудио-сообщение.
     */
    fun stopRecording() {
        try {
            audioRecorder.stopRecording()

            // Собираем все чанки в один ByteArray
            val totalSize = audioBuffer.sumOf { it.size }
            if (totalSize == 0) {
                _voiceState.update {
                    it.copy(
                        isRecording = false,
                        statusText = "Нет записанных данных",
                        amplitude = 0f
                    )
                }
                audioBuffer.clear()
                return
            }

            val audioBytes = ByteArray(totalSize)
            var offset = 0
            for (chunk in audioBuffer) {
                chunk.copyInto(audioBytes, offset)
                offset += chunk.size
            }
            audioBuffer.clear()

            Log.d(TAG, "Recording stopped — ${audioBytes.size} bytes, sending...")

            _voiceState.update {
                it.copy(
                    isRecording = false,
                    statusText = "Отправка...",
                    amplitude = 0f
                )
            }

            // Отправляем аудио-сообщение (шифрование внутри MessengerClient)
            val pubKey = serverPublicKey!!
            viewModelScope.launch {
                try {
                    messengerClient.sendAudioMessage(conversationId, audioBytes, pubKey)
                    _voiceState.update {
                        it.copy(statusText = "Ожидание ответа...")
                    }
                } catch (e: Exception) {
                    _voiceState.update {
                        it.copy(statusText = "Ошибка отправки: ${e.message}")
                    }
                    Log.e(TAG, "sendAudioMessage error", e)
                }
            }
        } catch (e: Exception) {
            _voiceState.update { it.copy(statusText = "Ошибка: ${e.message}") }
            Log.e(TAG, "stopRecording error", e)
        }
    }

    /**
     * Расшифровать аудио-сообщение и воспроизвести.
     */
    private fun decryptAndPlayAudio(msg: com.gorikon.openclawgkvoice.messenger.Message) {
        val pubKey = serverPublicKey
        if (pubKey == null) {
            Log.e(TAG, "Cannot decrypt — no server public key")
            return
        }

        viewModelScope.launch {
            try {
                val ciphertext = Base64.decode(msg.ciphertext, Base64.NO_WRAP)
                val credentials = authRepository.loadCredentials()
                if (credentials == null) {
                    Log.e(TAG, "No credentials for decryption")
                    return@launch
                }

                val plaintext = cryptoManager.openSealedText(
                    ciphertext = ciphertext,
                    publicKey = credentials.keyPair.publicKey,
                    secretKey = credentials.keyPair.secretKey
                )

                // Декодируем base64 из plaintext обратно в PCM
                val audioBytes = Base64.decode(plaintext, Base64.NO_WRAP)

                Log.d(TAG, "Audio decrypted — ${audioBytes.size} bytes, playing...")

                _voiceState.update {
                    it.copy(isPlaying = true, statusText = "Говорит...")
                }

                audioPlayer.playAudio(audioBytes)

                // Ждём окончания воспроизведения
                while (audioPlayer.isCurrentlyPlaying()) {
                    kotlinx.coroutines.delay(50)
                }

                _voiceState.update {
                    it.copy(isPlaying = false, statusText = "Готов к записи")
                }
            } catch (e: Exception) {
                _voiceState.update {
                    it.copy(
                        isPlaying = false,
                        statusText = "Ошибка воспроизведения: ${e.message}"
                    )
                }
                Log.e(TAG, "decryptAndPlayAudio error", e)
            }
        }
    }

    /**
     * Поставить запись на паузу.
     */
    fun pauseRecording() {
        audioRecorder.pause()
        _voiceState.update {
            it.copy(isRecording = false, statusText = "Пауза")
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioRecorder.stopRecording()
        audioPlayer.release()
    }
}
