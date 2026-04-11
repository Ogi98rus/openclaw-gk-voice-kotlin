package com.gorikon.openclawgkvoice.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.gorikon.openclawgkvoice.gateway.GatewayConfig
import com.gorikon.openclawgkvoice.gateway.GatewayStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

// Extension для DataStore gateway'ев
private val Context.gatewayDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "gateways"
)

/**
 * Ключи DataStore для gateway конфигов.
 */
object GatewayKeys {
    fun gatewayIdsListKey() = stringPreferencesKey("gateway_ids")
    fun gatewayConfigKey(gatewayId: String) = stringPreferencesKey("gateway_config_$gatewayId")
}

/**
 * Сериализуемая версия GatewayConfig (без EncryptedSharedPreferences храним только публичные поля).
 */
@Serializable
data class SerializableGatewayConfig(
    val id: String,
    val name: String,
    val url: String,
    val isActive: Boolean,
    val statusOrdinal: Int = 0
)

/**
 * Репозиторий для хранения gateway конфигов.
 *
 * Архитектура:
 * - Публичные данные (id, name, url, status) — DataStore Preferences
 * - Секретные данные (apiKey) — EncryptedSharedPreferences
 *
 * Это обеспечивает безопасность токенов при сохранении простоты работы с остальными данными.
 */
@Singleton
class GatewayRepository @Inject constructor(
    private val context: Context,
    private val encryptedPrefs: SharedPreferences
) {
    private val dataStore = context.gatewayDataStore
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Поток со списком всех gateway'ев.
     */
    val gateways: Flow<List<GatewayConfig>> = dataStore.data.map { prefs ->
        val idsJson = prefs[GatewayKeys.gatewayIdsListKey()] ?: return@map emptyList()
        val ids: List<String> = try {
            Json.decodeFromString(idsJson)
        } catch (e: Exception) {
            emptyList()
        }

        ids.mapNotNull { id ->
            val configJson = prefs[GatewayKeys.gatewayConfigKey(id)] ?: return@mapNotNull null
            val serializable = try {
                Json.decodeFromString<SerializableGatewayConfig>(configJson)
            } catch (e: Exception) {
                return@mapNotNull null
            }

            // API Key достаем из зашифрованного хранилища
            val apiKey = encryptedPrefs.getString("api_key_$id", "") ?: ""

            GatewayConfig(
                id = serializable.id,
                name = serializable.name,
                url = serializable.url,
                apiKey = apiKey,
                isActive = serializable.isActive,
                status = GatewayStatus.entries.getOrElse(serializable.statusOrdinal) { GatewayStatus.Disconnected }
            )
        }
    }

    /**
     * Получить все gateway'и синхронно (suspend).
     */
    suspend fun getGateways(): List<GatewayConfig> = gateways.first()

    /**
     * Сохранить gateway в хранилище.
     * Публичные данные → DataStore, API Key → EncryptedSharedPreferences.
     */
    suspend fun saveGateway(config: GatewayConfig) {
        val serializable = SerializableGatewayConfig(
            id = config.id,
            name = config.name,
            url = config.url,
            isActive = config.isActive,
            statusOrdinal = config.status.ordinal
        )

        val configJson = json.encodeToString(serializable)

        dataStore.edit { prefs ->
            // Обновляем список ID
            val idsJson = prefs[GatewayKeys.gatewayIdsListKey()] ?: "[]"
            val ids: MutableList<String> = try {
                Json.decodeFromString(idsJson).toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }

            if (!ids.contains(config.id)) {
                ids.add(config.id)
                prefs[GatewayKeys.gatewayIdsListKey()] = json.encodeToString(ids)
            }

            // Сохраняем конфиг
            prefs[GatewayKeys.gatewayConfigKey(config.id)] = configJson
        }

        // Сохраняем API Key в зашифрованном хранилище
        encryptedPrefs.edit()
            .putString("api_key_${config.id}", config.apiKey)
            .apply()
    }

    /**
     * Удалить gateway из хранилища.
     */
    suspend fun deleteGateway(gatewayId: String) {
        dataStore.edit { prefs ->
            // Удаляем ID из списка
            val idsJson = prefs[GatewayKeys.gatewayIdsListKey()] ?: "[]"
            val ids: MutableList<String> = try {
                Json.decodeFromString(idsJson).toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }

            ids.remove(gatewayId)
            prefs[GatewayKeys.gatewayIdsListKey()] = json.encodeToString(ids)

            // Удаляем конфиг
            prefs.remove(GatewayKeys.gatewayConfigKey(gatewayId))
        }

        // Удаляем API Key
        encryptedPrefs.edit()
            .remove("api_key_$gatewayId")
            .apply()
    }

    /**
     * Обновить статус gateway'я (без перезаписи API Key).
     */
    suspend fun updateGatewayStatus(gatewayId: String, status: GatewayStatus) {
        dataStore.edit { prefs ->
            val configJson = prefs[GatewayKeys.gatewayConfigKey(gatewayId)] ?: return@edit
            val serializable = try {
                Json.decodeFromString<SerializableGatewayConfig>(configJson)
            } catch (e: Exception) {
                return@edit
            }

            val updated = serializable.copy(statusOrdinal = status.ordinal)
            prefs[GatewayKeys.gatewayConfigKey(gatewayId)] = json.encodeToString(updated)
        }
    }

    /**
     * Сбросить isActive у всех gateway'ев и установить для одного.
     */
    suspend fun setActiveGateway(gatewayId: String) {
        val currentGateways = getGateways()
        currentGateways.forEach { gw ->
            val updated = gw.copy(isActive = gw.id == gatewayId)
            saveGateway(updated)
        }
    }
}

/**
 * Фабрика для создания EncryptedSharedPreferences.
 */
object EncryptedPrefsFactory {
    fun create(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            "gateway_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}
