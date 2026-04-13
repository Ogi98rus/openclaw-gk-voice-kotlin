package com.gorikon.openclawgkvoice.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// Extension для DataStore сервера
private val Context.serverDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "server"
)

/**
 * Ключи DataStore для настроек сервера.
 */
object ServerKeys {
    val SERVER_URL = stringPreferencesKey("server_url")
}

/**
 * Репозиторий для хранения URL сервера Messenger.
 *
 * Использует DataStore Preferences для надёжного хранения.
 */
@Singleton
class ServerRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.serverDataStore

    /**
     * Поток с текущим URL сервера.
     */
    val serverUrl: Flow<String?> = dataStore.data.map { prefs ->
        prefs[ServerKeys.SERVER_URL]
    }

    /**
     * Получить URL сервера синхронно (suspend).
     */
    suspend fun getServerUrl(): String? = serverUrl.first()

    /**
     * Сохранить URL сервера.
     */
    suspend fun setServerUrl(url: String) {
        dataStore.edit { prefs ->
            prefs[ServerKeys.SERVER_URL] = url.trim()
        }
    }

    /**
     * Очистить URL сервера.
     */
    suspend fun clearServerUrl() {
        dataStore.edit { prefs ->
            prefs.remove(ServerKeys.SERVER_URL)
        }
    }
}
