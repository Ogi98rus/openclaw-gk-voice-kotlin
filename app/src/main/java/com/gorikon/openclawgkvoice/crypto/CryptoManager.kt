package com.gorikon.openclawgkvoice.crypto

import android.util.Log
import org.libsodium.jni.NaCl
import org.libsodium.jni.Sodium
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Менеджер криптографии на основе libsodium (X25519 sealed box).
 *
 * Важные замечания по libsodium-jni-aar:
 * - `NaCl.sodium()` возвращает объект `Sodium!` (не Int!)
 * - Константы crypto_box: PUBLICKEYBYTES=32, SECRETKEYBYTES=32, SEALBYTES=48
 * - `crypto_box_seal` / `crypto_box_seal_open` принимают длину как `Int` (не Long!)
 */
@Singleton
class CryptoManager @Inject constructor() {

    companion object {
        private const val TAG = "CryptoManager"
        // X25519 константы — фиксированные значения libsodium
        const val PUBLICKEYBYTES = 32
        const val SECRETKEYBYTES = 32
        const val SEALBYTES = 48
    }

    private var _isInitialized = false
    val isInitialized: Boolean get() = _isInitialized

    init {
        try {
            // NaCl.sodium() возвращает объект Sodium, проверяем не null
            val ret = NaCl.sodium()
            if (ret != null) {
                _isInitialized = true
                Log.i(TAG, "libsodium initialized successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize libsodium", e)
        }
    }

    /**
     * Генерация пары ключей X25519.
     */
    fun generateKeyPair(): KeyPair {
        checkInitialized()
        val publicKey = ByteArray(PUBLICKEYBYTES)
        val secretKey = ByteArray(SECRETKEYBYTES)
        val result = Sodium.crypto_box_keypair(publicKey, secretKey)
        if (result != 0) {
            throw IllegalStateException("Failed to generate keypair: $result")
        }
        return KeyPair(publicKey = publicKey, secretKey = secretKey)
    }

    /**
     * Зашифровать sealed box (crypto_box_seal).
     */
    fun sealMessage(plaintext: ByteArray, recipientPublicKey: ByteArray): ByteArray {
        checkInitialized()
        val ciphertext = ByteArray(plaintext.size + SEALBYTES)
        val result = Sodium.crypto_box_seal(
            ciphertext,
            plaintext,
            plaintext.size, // Int, не Long!
            recipientPublicKey
        )
        if (result != 0) {
            throw IllegalStateException("Failed to seal message: $result")
        }
        return ciphertext
    }

    fun sealText(text: String, recipientPublicKey: ByteArray): ByteArray {
        return sealMessage(text.encodeToByteArray(), recipientPublicKey)
    }

    /**
     * Расшифровать sealed box (crypto_box_seal_open).
     */
    fun openSealedMessage(
        ciphertext: ByteArray,
        publicKey: ByteArray,
        secretKey: ByteArray
    ): ByteArray {
        checkInitialized()
        val plaintextSize = ciphertext.size - SEALBYTES
        if (plaintextSize <= 0) {
            throw IllegalArgumentException("Ciphertext too short")
        }
        val plaintext = ByteArray(plaintextSize)
        val result = Sodium.crypto_box_seal_open(
            plaintext,
            ciphertext,
            ciphertext.size, // Int, не Long!
            publicKey,
            secretKey
        )
        if (result != 0) {
            throw IllegalStateException("Failed to open sealed message: $result")
        }
        return plaintext
    }

    fun openSealedText(
        ciphertext: ByteArray,
        publicKey: ByteArray,
        secretKey: ByteArray
    ): String {
        return openSealedMessage(ciphertext, publicKey, secretKey).decodeToString()
    }

    private fun checkInitialized() {
        if (!_isInitialized) {
            throw IllegalStateException("libsodium not initialized")
        }
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
