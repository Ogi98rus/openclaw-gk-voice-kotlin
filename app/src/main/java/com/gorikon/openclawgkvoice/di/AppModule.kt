package com.gorikon.openclawgkvoice.di

import android.content.Context
import android.content.SharedPreferences
import com.gorikon.openclawgkvoice.gateway.GatewayClient
import com.gorikon.openclawgkvoice.gateway.GatewayManager
import com.gorikon.openclawgkvoice.storage.EncryptedPrefsFactory
import com.gorikon.openclawgkvoice.storage.GatewayRepository
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
     * EncryptedSharedPreferences для хранения API ключей.
     * MasterKey генерируется в Android Keystore (AES256_GCM).
     */
    @Provides
    @Singleton
    fun provideEncryptedSharedPreferences(
        @ApplicationContext context: Context
    ): SharedPreferences {
        return EncryptedPrefsFactory.create(context)
    }

    /**
     * GatewayClient — WebSocket клиент.
     */
    @Provides
    @Singleton
    fun provideGatewayClient(
        okHttpClient: OkHttpClient
    ): GatewayClient {
        return GatewayClient(okHttpClient)
    }

    /**
     * GatewayManager — мультигейтвей менеджер.
     * GatewayRepository инжектится в HomeViewModel напрямую (не через GatewayManager).
     */
    @Provides
    @Singleton
    fun provideGatewayManager(
        gatewayClient: GatewayClient
    ): GatewayManager {
        return GatewayManager(gatewayClient)
    }

    /**
     * GatewayRepository — хранилище gateway конфигов.
     */
    @Provides
    @Singleton
    fun provideGatewayRepository(
        @ApplicationContext context: Context,
        encryptedPrefs: SharedPreferences
    ): GatewayRepository {
        return GatewayRepository(context, encryptedPrefs)
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
}
