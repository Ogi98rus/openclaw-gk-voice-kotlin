package com.gorikon.openclawgkvoice.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.gorikon.openclawgkvoice.crypto.KeyPair
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Сериализуемые учётные данные (без секретного ключа — он хранится отдельно).
 */
@Serializable
data class SerializableCredentials(
    val token: String,
    val userId: String,
    val deviceId: String,
    val serverPublicKey: String,  // Base64
    val publicKey: String          // Base64
)

/**
 * Полные учётные данные с секретным ключом.
 */
data class Credentials(
    val token: String,
    val userId: String,
    val deviceId: String,
    val serverPublicKey: ByteArray,
    val keyPair: KeyPair
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Credentials
        return token == other.token &&
               userId == other.userId &&
               deviceId == other.deviceId &&
               serverPublicKey.contentEquals(other.serverPublicKey) &&
               keyPair == other.keyPair
    }

    override fun hashCode(): Int {
        var result = token.hashCode()
        result = 31 * result + userId.hashCode()
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + serverPublicKey.contentHashCode()
        result = 31 * result + keyPair.hashCode()
        return result
    }
}

/**
 * Репозиторий для хранения учётных данных аутентификации.
 *
 * Архитектура:
 * - JWT токен, userId, deviceId, публичные ключи — EncryptedSharedPreferences
 * - Секретный ключ клиента — EncryptedSharedPreferences (отдельный ключ)
 * - Публичный ключ сервера — EncryptedSharedPreferences
 *
 * Всё хранится в зашифрованном виде через Android Keystore.
 */
@Singleton
class AuthRepository @Inject constructor(
    private val context: Context
) {
    private val encryptedPrefs: SharedPreferences by lazy {
        createEncryptedPrefs()
    }

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val PREFS_NAME = "auth_secrets"
        private const val KEY_CREDS = "credentials"
        private const val KEY_SECRET_KEY = "client_secret_key"
    }

    /**
     * Сохранить учётные данные после успешной авторизации.
     */
    suspend fun saveCredentials(
        token: String,
        userId: String,
        deviceId: String,
        serverPublicKey: ByteArray,
        keyPair: KeyPair
    ) {
        val serializable = SerializableCredentials(
            token = token,
            userId = userId,
            deviceId = deviceId,
            serverPublicKey = android.util.Base64.encodeToString(serverPublicKey, android.util.Base64.NO_WRAP),
            publicKey = android.util.Base64.encodeToString(keyPair.publicKey, android.util.Base64.NO_WRAP)
        )

        val credsJson = json.encodeToString(serializable)

        encryptedPrefs.edit()
            .putString(KEY_CREDS, credsJson)
            .putString(KEY_SECRET_KEY, android.util.Base64.encodeToString(keyPair.secretKey, android.util.Base64.NO_WRAP))
            .apply()
    }

    /**
     * Загрузить сохранённые учётные данные.
     * Возвращает null если не авторизован.
     */
    fun loadCredentials(): Credentials? {
        return try {
            val credsJson = encryptedPrefs.getString(KEY_CREDS, null) ?: return null
            val serializable = json.decodeFromString<SerializableCredentials>(credsJson)

            val secretKeyBase64 = encryptedPrefs.getString(KEY_SECRET_KEY, null) ?: return null
            val secretKey = android.util.Base64.decode(secretKeyBase64, android.util.Base64.NO_WRAP)
            val serverPublicKey = android.util.Base64.decode(serializable.serverPublicKey, android.util.Base64.NO_WRAP)
            val publicKey = android.util.Base64.decode(serializable.publicKey, android.util.Base64.NO_WRAP)

            Credentials(
                token = serializable.token,
                userId = serializable.userId,
                deviceId = serializable.deviceId,
                serverPublicKey = serverPublicKey,
                keyPair = KeyPair(publicKey = publicKey, secretKey = secretKey)
            )
        } catch (e: Exception) {
            android.util.Log.e("AuthRepository", "Failed to load credentials", e)
            null
        }
    }

    /**
     * Получить JWT токен (для WebSocket авторизации).
     */
    fun getToken(): String? {
        return loadCredentials()?.token
    }

    /**
     * Проверить, авторизован ли пользователь.
     */
    fun isLoggedIn(): Boolean {
        return loadCredentials() != null
    }

    /**
     * Очистить все учётные данные (выход).
     */
    suspend fun clearCredentials() {
        encryptedPrefs.edit()
            .remove(KEY_CREDS)
            .remove(KEY_SECRET_KEY)
            .apply()
    }

    /**
     * Создать EncryptedSharedPreferences через Android Keystore.
     */
    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}
