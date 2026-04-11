package com.gorikon.openclawgkvoice.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Callback для отслеживания состояния воспроизведения.
 */
interface AudioPlayerCallback {
    /** Воспроизведение началось */
    fun onPlaybackStarted()
    /** Воспроизведение завершилось */
    fun onPlaybackFinished()
}

/**
 * Воспроизведение аудио, полученного от gateway (TTS ответ агента).
 *
 * Использует AudioTrack для потокового воспроизведения PCM 16-bit данных.
 * Настройки соответствуют параметрам записи: 16kHz, mono, PCM 16-bit.
 *
 * Инжектится через Hilt. Callback устанавливается через setCallback().
 */
class AudioPlayer {
    private var audioTrack: AudioTrack? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var playbackJob: Job? = null
    private var callback: AudioPlayerCallback? = null

    // Параметры воспроизведения
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    // Размер буфера
    private val bufferSize by lazy {
        AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            .coerceAtLeast(sampleRate) // Минимум 62.5мс
    }

    private var isPlaying = false

    /**
     * Установить callback для отслеживания состояния воспроизведения.
     */
    fun setCallback(cb: AudioPlayerCallback?) {
        this.callback = cb
    }

    /**
     * Воспроизвести аудио-данные из ByteArray (PCM 16-bit).
     * Данные ставятся в очередь на воспроизведение.
     */
    fun play(data: ByteArray) {
        if (data.isEmpty()) return

        // Если уже играет — дописываем данные в существующий трек
        if (isPlaying && audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
            audioTrack?.write(data, 0, data.size)
            return
        }

        // Создаём новый AudioTrack
        try {
            audioTrack?.release()

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .setEncoding(audioFormat)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize.coerceAtLeast(data.size))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            isPlaying = true
            callback?.onPlaybackStarted()

            playbackJob = scope.launch {
                audioTrack?.write(data, 0, data.size)

                // Ждём окончания воспроизведения
                while (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    kotlinx.coroutines.delay(50)
                }
                isPlaying = false
                callback?.onPlaybackFinished()
            }
        } catch (e: Exception) {
            isPlaying = false
            callback?.onPlaybackFinished()
        }
    }

    /**
     * Начать потоковое воспроизведение (streaming mode).
     * Вызывается один раз, затем данные дописываются через write().
     */
    fun startStreaming() {
        if (isPlaying) return

        try {
            audioTrack?.release()

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .setEncoding(audioFormat)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            isPlaying = true
            callback?.onPlaybackStarted()
        } catch (e: Exception) {
            isPlaying = false
            callback?.onPlaybackFinished()
        }
    }

    /**
     * Дописать аудио-данные в поток (для streaming mode).
     */
    fun write(data: ByteArray) {
        if (isPlaying && audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
            audioTrack?.write(data, 0, data.size)
        }
    }

    /**
     * Остановить воспроизведение.
     */
    fun stop() {
        isPlaying = false
        playbackJob?.cancel()
        playbackJob = null

        try {
            audioTrack?.stop()
        } catch (e: IllegalStateException) {
            // Уже остановлен
        }
        audioTrack?.release()
        audioTrack = null

        callback?.onPlaybackFinished()
    }

    /**
     * Поставить на паузу.
     */
    fun pause() {
        if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
            audioTrack?.pause()
        }
    }

    /**
     * Возобновить воспроизведение после паузы.
     */
    fun resume() {
        if (audioTrack?.playState == AudioTrack.PLAYSTATE_PAUSED) {
            audioTrack?.play()
        }
    }

    /**
     * Флаг: идёт ли воспроизведение.
     */
    fun isCurrentlyPlaying(): Boolean = isPlaying

    /**
     * Освободить все ресурсы.
     */
    fun release() {
        stop()
    }
}
