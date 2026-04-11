package com.gorikon.openclawgkvoice.ui.screens

import androidx.lifecycle.ViewModel
import com.gorikon.openclawgkvoice.gateway.VoiceState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * ViewModel экрана голосовой связи.
 *
 * Управляет состоянием записи, воспроизведения и подключением к gateway.
 */
@HiltViewModel
class VoiceViewModel @Inject constructor() : ViewModel() {

    private val _voiceState = MutableStateFlow(VoiceState())
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    /**
     * Начать запись голоса.
     * В реальной реализации: audioRecorder.startRecording()
     */
    fun startRecording() {
        _voiceState.update {
            it.copy(
                isRecording = true,
                statusText = "Запись...",
                connectionStatus = it.connectionStatus
            )
        }
    }

    /**
     * Остановить запись и отправить аудио на gateway.
     */
    fun stopRecording() {
        _voiceState.update {
            it.copy(
                isRecording = false,
                statusText = "Обработка...",
                amplitude = 0f
            )
        }

        // После отправки — ожидаем ответ агента
        _voiceState.update {
            it.copy(
                statusText = "Нажмите для записи",
                isPlaying = false
            )
        }
    }

    /**
     * Поставить запись на паузу.
     */
    fun pauseRecording() {
        _voiceState.update {
            it.copy(
                isRecording = false,
                statusText = "Пауза"
            )
        }
    }

    /**
     * Обновить амплитуду (вызывается из AudioRecorder callback).
     */
    fun updateAmplitude(amplitude: Float) {
        _voiceState.update {
            it.copy(amplitude = amplitude)
        }
    }

    /**
     * Обновить статус подключения.
     */
    fun updateConnectionStatus(status: com.gorikon.openclawgkvoice.gateway.GatewayStatus) {
        _voiceState.update {
            it.copy(connectionStatus = status)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Очистка ресурсов — в реальной реализации остановить запись
    }
}
