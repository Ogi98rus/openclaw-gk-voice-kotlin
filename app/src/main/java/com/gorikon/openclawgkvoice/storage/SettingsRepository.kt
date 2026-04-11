package com.gorikon.openclawgkvoice.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// Extension для DataStore настроек
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings"
)

/**
 * Модель настроек приложения.
 */
data class AppSettings(
    val isDarkTheme: Boolean = true,
    val autoConnect: Boolean = true,
    val voiceLanguage: String = "ru"
)

/**
 * Репозиторий настроек приложения.
 * Хранит: тема, автоподключение, язык голоса.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.settingsDataStore

    private object Keys {
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        val VOICE_LANGUAGE = stringPreferencesKey("voice_language")
    }

    /**
     * Поток с текущими настройками.
     */
    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            isDarkTheme = prefs[Keys.DARK_THEME] ?: true,
            autoConnect = prefs[Keys.AUTO_CONNECT] ?: true,
            voiceLanguage = prefs[Keys.VOICE_LANGUAGE] ?: "ru"
        )
    }

    /**
     * Включить/выключить тёмную тему.
     */
    suspend fun setDarkTheme(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.DARK_THEME] = enabled
        }
    }

    /**
     * Включить/выключить автоподключение к активному gateway'ю.
     */
    suspend fun setAutoConnect(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.AUTO_CONNECT] = enabled
        }
    }

    /**
     * Установить язык голоса (ru, en, de, ...).
     */
    suspend fun setVoiceLanguage(language: String) {
        dataStore.edit { prefs ->
            prefs[Keys.VOICE_LANGUAGE] = language
        }
    }
}
