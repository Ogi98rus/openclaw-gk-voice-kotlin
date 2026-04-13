package com.gorikon.openclawgkvoice.di

import android.content.Context
import com.gorikon.openclawgkvoice.audio.AudioPlayer
import com.gorikon.openclawgkvoice.audio.AudioRecorder
import com.gorikon.openclawgkvoice.crypto.CryptoManager
import com.gorikon.openclawgkvoice.messenger.MessengerClient
import com.gorikon.openclawgkvoice.storage.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Singleton-модуль: зависимости, которые живут всё время работы приложения.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * OkHttpClient с настроенным таймаутом и WebSocket поддержкой.
     * readTimeout = 0 — бесконечное чтение для WebSocket.
     * pingInterval = 30s — TCP ping для поддержания соединения.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)
            .build()
    }

    /**
     * CryptoManager — libsodium X25519 sealed box.
     */
    @Provides
    @Singleton
    fun provideCryptoManager(): CryptoManager {
        return CryptoManager()
    }

    /**
     * MessengerClient — WebSocket клиент для Messenger Server.
     */
    @Provides
    @Singleton
    fun provideMessengerClient(
        okHttpClient: OkHttpClient,
        cryptoManager: CryptoManager
    ): MessengerClient {
        return MessengerClient(okHttpClient, cryptoManager)
    }

    /**
     * SettingsRepository — хранилище настроек.
     */
    @Provides
    @Singleton
    fun provideSettingsRepository(
        @ApplicationContext context: Context
    ): SettingsRepository {
        return SettingsRepository(context)
    }

    /**
     * AudioRecorder — для записи PCM 16kHz с микрофона.
     * Singleton: один экземпляр на всё приложение.
     */
    @Provides
    @Singleton
    fun provideAudioRecorder(
        @ApplicationContext context: Context
    ): AudioRecorder {
        return AudioRecorder(context)
    }

    /**
     * AudioPlayer — для воспроизведения аудио-ответов.
     * Singleton: один экземпляр на всё приложение.
     */
    @Provides
    @Singleton
    fun provideAudioPlayer(): AudioPlayer {
        return AudioPlayer()
    }
}
