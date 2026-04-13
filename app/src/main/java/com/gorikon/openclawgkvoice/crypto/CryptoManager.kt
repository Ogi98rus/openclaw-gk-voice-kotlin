package com.gorikon.openclawgkvoice.crypto

import android.util.Log
import org.libsodium.jni.NaCl
import org.libsodium.jni.Sodium
import org.libsodium.jni.SodiumConstants
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Менеджер криптографии на основе libsodium (X25519).
 *
 * Использует sealed box (crypto_box_seal) для E2E шифрования:
 * - Клиент шифрует сообщения публичным ключом сервера
 * - Только сервер может расшифровать своим секретным ключом
 *
 * Инициализирует libsodium при первом использовании.
 */
@Singleton
class CryptoManager @Inject constructor() {

    private var initialized = false

    init {
        try {
            // Инициализация libsodium — загружает native библиотеку
            val ret = NaCl.sodium()
            if (ret != 0) {
                Log.e(TAG, "Failed to initialize libsodium: $ret")
            } else {
                initialized = true
                Log.i(TAG, "libsodium initialized successfully")
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "libsodium native library not found: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error initializing libsodium: ${e.message}")
        }
    }

    /**
     * Сгенерировать новую пару ключей X25519.
     *
     * @return KeyPair с публичным и секретным ключами (raw bytes)
     */
    fun generateKeyPair(): KeyPair {
        checkInitialized()

        val publicKey = ByteArray(SodiumConstants.PUBLICKEYBYTES)
        val secretKey = ByteArray(SodiumConstants.SECRETKEYBYTES)

        val result = Sodium.crypto_box_keypair(publicKey, secretKey)
        if (result != 0) {
            throw IllegalStateException("Failed to generate keypair: $result")
        }

        return KeyPair(
            publicKey = publicKey,
            secretKey = secretKey
        )
    }

    /**
     * Зашифровать сообщение sealed box (crypto_box_seal).
     *
     * Шифрует plaintext публичным ключом получателя.
     * Только владелец соответствующего секретного ключа может расшифровать.
     *
     * @param plaintext Исходное сообщение (UTF-8 bytes)
     * @param recipientPublicKey Публичный ключ получателя
     * @return Зашифрованный ciphertext
     */
    fun sealMessage(plaintext: ByteArray, recipientPublicKey: ByteArray): ByteArray {
        checkInitialized()

        // ciphertext size = message + SEALEDBYTES
        val ciphertext = ByteArray(plaintext.size + SodiumConstants.SEALEDBYTES)

        val result = Sodium.crypto_box_seal(
            ciphertext,
            plaintext,
            plaintext.size.toLong(),
            recipientPublicKey
        )

        if (result != 0) {
            throw IllegalStateException("Failed to seal message: $result")
        }

        return ciphertext
    }

    /**
     * Зашифровать текстовое сообщение sealed box.
     *
     * @param text Исходный текст
     * @param recipientPublicKey Публичный ключ получателя
     * @return Зашифрованный ciphertext (ByteArray)
     */
    fun sealText(text: String, recipientPublicKey: ByteArray): ByteArray {
        return sealMessage(text.encodeToByteArray(), recipientPublicKey)
    }

    /**
     * Расшифровать sealed box сообщение (crypto_box_seal_open).
     *
     * @param ciphertext Зашифрованное сообщение
     * @param publicKey Наш публичный ключ
     * @param secretKey Наш секретный ключ
     * @return Расшифрованный plaintext
     */
    fun openSealedMessage(
        ciphertext: ByteArray,
        publicKey: ByteArray,
        secretKey: ByteArray
    ): ByteArray {
        checkInitialized()

        // plaintext size = ciphertext - SEALEDBYTES
        val plaintextSize = ciphertext.size - SodiumConstants.SEALEDBYTES
        if (plaintextSize <= 0) {
            throw IllegalArgumentException("Ciphertext too short to be a sealed box message")
        }

        val plaintext = ByteArray(plaintextSize)

        val result = Sodium.crypto_box_seal_open(
            plaintext,
            ciphertext,
            ciphertext.size.toLong(),
            publicKey,
            secretKey
        )

        if (result != 0) {
            throw IllegalStateException("Failed to open sealed message: $result")
        }

        return plaintext
    }

    /**
     * Расшифровать sealed box сообщение в текст.
     */
    fun openSealedText(
        ciphertext: ByteArray,
        publicKey: ByteArray,
        secretKey: ByteArray
    ): String {
        return openSealedMessage(ciphertext, publicKey, secretKey).decodeToString()
    }

    /**
     * Получить размер публичного ключа X25519 в байтах.
     */
    fun getPublicKeySize(): Int = SodiumConstants.PUBLICKEYBYTES

    /**
     * Получить размер секретного ключа X25519 в байтах.
     */
    fun getSecretKeySize(): Int = SodiumConstants.SECRETKEYBYTES

    /**
     * Получить размер sealed box overhead в байтах.
     */
    fun getSealedOverhead(): Int = SodiumConstants.SEALEDBYTES

    /**
     * Инициализирован ли libsodium.
     */
    fun isInitialized(): Boolean = initialized

    private fun checkInitialized() {
        if (!initialized) {
            throw IllegalStateException("libsodium not initialized")
        }
    }

    companion object {
        private const val TAG = "CryptoManager"
    }
}

/**
 * Пара ключей X25519.
 */
data class KeyPair(
    val publicKey: ByteArray,
    val secretKey: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as KeyPair
        return publicKey.contentEquals(other.publicKey) &&
               secretKey.contentEquals(other.secretKey)
    }

    override fun hashCode(): Int {
        var result = publicKey.contentHashCode()
        result = 31 * result + secretKey.contentHashCode()
        return result
    }
}
