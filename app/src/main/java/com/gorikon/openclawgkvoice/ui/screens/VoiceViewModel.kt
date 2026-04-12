package com.gorikon.openclawgkvoice.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gorikon.openclawgkvoice.audio.AudioPlayer
import com.gorikon.openclawgkvoice.audio.AudioRecorder
import com.gorikon.openclawgkvoice.audio.AudioRecorderCallback
import com.gorikon.openclawgkvoice.gateway.GatewayCallback
import com.gorikon.openclawgkvoice.gateway.GatewayClient
import com.gorikon.openclawgkvoice.gateway.GatewayManager
import com.gorikon.openclawgkvoice.gateway.GatewayStatus
import com.gorikon.openclawgkvoice.gateway.VoiceState
import com.gorikon.openclawgkvoice.service.VoiceRecordingService
import com.gorikon.openclawgkvoice.util.PermissionHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel экрана голосовой связи.
 *
 * Полная реализация:
 * - AudioRecorder для записи PCM 16kHz
 * - GatewayClient для WebSocket подключения
 * - AudioPlayer для воспроизведения ответов
 * - ForegroundService для записи в фоне
 * - Runtime permissions для RECORD_AUDIO
 */
@HiltViewModel
class VoiceViewModel @Inject constructor(
    application: Application,
    private val gatewayClient: GatewayClient,
    private val gatewayManager: GatewayManager,
    private val audioRecorder: AudioRecorder,
    private val audioPlayer: AudioPlayer
) : AndroidViewModel(application) {

    private val _voiceState = MutableStateFlow(VoiceState())
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    // Gateway callback: получаем аудио-ответы от gateway
    private val gatewayCallback = object : GatewayCallback {
        override fun onConnected() {
            _voiceState.update { it.copy(statusText = "Подключено", connectionStatus = GatewayStatus.Connected) }
        }

        override fun onDisconnected(code: Int, reason: String) {
            _voiceState.update { it.copy(statusText = "Отключено", connectionStatus = GatewayStatus.Disconnected) }
        }

        override fun onMessage(text: String) {
            _voiceState.update { it.copy(statusText = text) }
        }

        override fun onAudio(data: ByteArray) {
            viewModelScope.launch {
                try {
                    _voiceState.update { it.copy(isPlaying = true, statusText = "Говорит...") }
                    audioPlayer.play(data)
                    // Ждём окончания воспроизведения
                    while (audioPlayer.isCurrentlyPlaying()) {
                        kotlinx.coroutines.delay(50)
                    }
                    _voiceState.update { it.copy(isPlaying = false, statusText = "Нажмите для записи") }
                } catch (e: Exception) {
                    _voiceState.update { it.copy(statusText = "Ошибка воспроизведения") }
                }
            }
        }

        override fun onError(error: Throwable) {
            _voiceState.update { it.copy(statusText = "Ошибка: ${error.message}", connectionStatus = GatewayStatus.Error) }
        }

        override fun onStatusChanged(status: GatewayStatus) {
            _voiceState.update { it.copy(connectionStatus = status) }
        }
    }

    // Audio callback: отправляем записанные чанки на gateway
    private val audioRecorderCallback = object : AudioRecorderCallback {
        override fun onAudioChunk(data: ByteArray) {
            val activeGw = gatewayManager.activeGateway.value
            if (activeGw != null && activeGw.status == GatewayStatus.Connected) {
                gatewayClient.sendAudio(data)
            }
        }

        override fun onAmplitudeChanged(amplitude: Float) {
            _voiceState.update { it.copy(amplitude = amplitude) }
        }

        override fun onError(error: String) {
            _voiceState.update { it.copy(statusText = "Ошибка записи: $error") }
        }
    }

    init {
        // Подписываемся на активный gateway
        viewModelScope.launch {
            gatewayManager.activeGateway.collect { gateway ->
                if (gateway != null) {
                    _voiceState.update {
                        it.copy(
                            connectionStatus = gateway.status,
                            statusText = when (gateway.status) {
                                GatewayStatus.Connected -> "Готов к записи"
                                GatewayStatus.Connecting -> "Подключение..."
                                GatewayStatus.Error -> "Ошибка подключения"
                                else -> "Выберите gateway"
                            }
                        )
                    }
                }
            }
        }
    }

    /**
     * Начать запись голоса.
     * Проверяет permission, запускает AudioRecorder и ForegroundService.
     */
    fun startRecording() {
        val context = getApplication<android.app.Application>()

        viewModelScope.launch {
            // Проверяем permission
            if (!PermissionHelper.checkAudioPermission(context)) {
                _voiceState.update { it.copy(statusText = "Требуется разрешение на запись") }
                return@launch
            }

            // Проверяем что есть активный gateway
            val activeGateway = gatewayManager.activeGateway.value
            if (activeGateway == null || activeGateway.status != GatewayStatus.Connected) {
                _voiceState.update { it.copy(statusText = "Сначала подключитесь к gateway") }
                return@launch
            }

            try {
                // Подключаемся если ещё не подключены
                if (gatewayClient.getCurrentConfig()?.id != activeGateway.id) {
                    gatewayClient.connect(activeGateway, gatewayCallback)
                }

                // Запускаем ForegroundService для записи в фоне
                VoiceRecordingService.start(context)

                audioRecorder.setCallback(audioRecorderCallback)
                audioRecorder.startRecording()
                _voiceState.update {
                    it.copy(
                        isRecording = true,
                        statusText = "Запись...",
                        connectionStatus = activeGateway.status
                    )
                }
            } catch (e: Exception) {
                _voiceState.update { it.copy(statusText = "Ошибка: ${e.message}") }
            }
        }
    }

    /**
     * Остановить запись.
     */
    fun stopRecording() {
        val context = getApplication<android.app.Application>()

        viewModelScope.launch {
            try {
                audioRecorder.stopRecording()
                VoiceRecordingService.stop(context)

                _voiceState.update {
                    it.copy(
                        isRecording = false,
                        statusText = "Ожидание ответа...",
                        amplitude = 0f
                    )
                }
            } catch (e: Exception) {
                _voiceState.update { it.copy(statusText = "Ошибка: ${e.message}") }
            }
        }
    }

    /**
     * Поставить запись на паузу.
     */
    fun pauseRecording() {
        val context = getApplication<android.app.Application>()

        viewModelScope.launch {
            audioRecorder.pause()
            VoiceRecordingService.pause(context)
            _voiceState.update {
                it.copy(isRecording = false, statusText = "Пауза")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioRecorder.stopRecording()
        gatewayClient.disconnect()
        audioPlayer.release()
    }
}
