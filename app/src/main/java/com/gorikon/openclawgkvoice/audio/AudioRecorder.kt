package com.gorikon.openclawgkvoice.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Callback для получения аудио-данных во время записи.
 */
interface AudioRecorderCallback {
    /** Вызывается при каждом заполнении буфера записи */
    fun onAudioChunk(data: ByteArray)
    /** Вызывается при изменении средней амплитуды (для waveform визуализации) */
    fun onAmplitudeChanged(amplitude: Float)
    /** Вызывается при ошибке записи */
    fun onError(error: String)
}

/**
 * Запись аудио с микрофона для отправки на gateway.
 *
 * Настройки оптимизированы под Whisper STT:
 * - Sample rate: 16000 Гц
 * - Channels: Mono
 * - Encoding: PCM 16-bit
 *
 * Использует AudioRecord (а не MediaRecorder), т.к. нужен потоковый доступ к PCM-данным.
 *
 * Инжектится через Hilt с Application Context. Callback устанавливается через setCallback().
 */
@Singleton
class AudioRecorder @Inject constructor(
    private val context: Context
) {
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var callback: AudioRecorderCallback? = null

    // Параметры записи
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    // Размер буфера вычисляем динамически
    private val bufferSize by lazy {
        AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            .coerceAtLeast(sampleRate * 2) // Минимум 125мс буфер
    }

    private var isRecording = false

    /**
     * Установить callback для получения аудио-данных.
     */
    fun setCallback(cb: AudioRecorderCallback?) {
        this.callback = cb
    }

    /**
     * Начать запись с микрофона.
     * Запускает фоновую корутину, которая читает аудио-чанки и отправляет в callback.
     */
    fun startRecording() {
        if (isRecording) return

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, // Оптимизировано для голоса
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                callback?.onError("Не удалось инициализировать AudioRecord")
                return
            }

            audioRecord?.startRecording()
            isRecording = true

            // Запускаем чтение аудио в фоне
            recordingJob = scope.launch {
                val buffer = ByteArray(bufferSize)
                while (isActive && isRecording) {
                    val read = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                    if (read > 0) {
                        val chunk = buffer.copyOf(read)
                        callback?.onAudioChunk(chunk)

                        // Вычисляем амплитуду для визуализации
                        val amplitude = calculateAmplitude(chunk)
                        callback?.onAmplitudeChanged(amplitude)
                    }
                }
            }
        } catch (e: SecurityException) {
            callback?.onError("Нет разрешения на запись аудио")
        } catch (e: Exception) {
            callback?.onError("Ошибка записи: ${e.message}")
        }
    }

    /**
     * Остановить запись и освободить ресурсы.
     */
    fun stopRecording() {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
        } catch (e: IllegalStateException) {
            // Уже остановлен — игнорируем
        }
        audioRecord?.release()
        audioRecord = null

        // Сбрасываем амплитуду
        callback?.onAmplitudeChanged(0f)
    }

    /**
     * Поставить запись на паузу.
     * Сохраняет AudioRecord для быстрого возобновления.
     */
    fun pause() {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
        } catch (e: IllegalStateException) {
            // Уже остановлен
        }
    }

    /**
     * Возобновить запись после паузы.
     */
    fun resume() {
        if (isRecording) return

        try {
            audioRecord?.startRecording()
            isRecording = true

            recordingJob = scope.launch {
                val buffer = ByteArray(bufferSize)
                while (isActive && isRecording) {
                    val read = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                    if (read > 0) {
                        val chunk = buffer.copyOf(read)
                        callback?.onAudioChunk(chunk)
                        val amplitude = calculateAmplitude(chunk)
                        callback?.onAmplitudeChanged(amplitude)
                    }
                }
            }
        } catch (e: Exception) {
            callback?.onError("Ошибка возобновления: ${e.message}")
        }
    }

    /**
     * Флаг: идёт ли запись прямо сейчас.
     */
    fun isCurrentlyRecording(): Boolean = isRecording

    /**
     * Освободить все ресурсы.
     */
    fun release() {
        stopRecording()
        scope.cancel()
    }

    /**
     * Вычислить среднеквадратичную амплитуду из PCM 16-bit данных.
     * Нормализует к диапазону 0.0..1.0.
     */
    private fun calculateAmplitude(data: ByteArray): Float {
        if (data.isEmpty()) return 0f

        var sumSquares = 0.0
        val samples = data.size / 2 // 2 байта на сэмпл (16-bit)

        for (i in 0 until samples) {
            val idx = i * 2
            // PCM 16-bit little-endian
            val sample = (data[idx].toInt() and 0xFF) or (data[idx + 1].toInt() shl 8)
            val normalized = sample.toDouble() / Short.MAX_VALUE
            sumSquares += normalized * normalized
        }

        val rms = sqrt(sumSquares / samples)
        return rms.toFloat().coerceIn(0f, 1f)
    }
}
