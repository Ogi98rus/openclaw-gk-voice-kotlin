package com.gorikon.openclawgkvoice.ui.screens

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gorikon.openclawgkvoice.auth.AuthRepository
import com.gorikon.openclawgkvoice.crypto.CryptoManager
import com.gorikon.openclawgkvoice.messenger.MessengerClient
import com.gorikon.openclawgkvoice.storage.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject

private const val TAG = "PairingViewModel"

sealed class PairingState {
    data object Idle : PairingState()
    data object Pairing : PairingState()
    data object Success : PairingState()
    data class Error(val message: String) : PairingState()
}

@HiltViewModel
class PairingViewModel @Inject constructor(
    private val cryptoManager: CryptoManager,
    private val authRepository: AuthRepository,
    private val serverRepository: ServerRepository,
    private val messengerClient: MessengerClient
) : ViewModel() {

    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state.asStateFlow()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    init {
        // Если уже авторизован — можно сразу перейти
        viewModelScope.launch {
            if (authRepository.isLoggedIn()) {
                _state.value = PairingState.Success
            }
        }
    }

    /**
     * Сопряжение с сервером через pairing code.
     *
     * Flow:
     * 1. Генерируем key pair
     * 2. POST /api/auth/pair с code + publicKey + deviceName
     * 3. Сохраняем credentials
     * 4. Сохраняем server URL
     * 5. Подключаемся к WebSocket
     */
    fun pair(serverUrl: String, deviceName: String, pairingCode: String) {
        viewModelScope.launch {
            _state.value = PairingState.Pairing

            try {
                // 1. Генерируем key pair
                val keyPair = cryptoManager.generateKeyPair()
                val publicKeyB64 = android.util.Base64.encodeToString(
                    keyPair.publicKey,
                    android.util.Base64.NO_WRAP
                )

                // 2. POST /api/auth/pair
                val json = JSONObject().apply {
                    put("code", pairingCode)
                    put("publicKey", publicKeyB64)
                    put("deviceName", deviceName)
                }

                val baseUrl = serverUrl.trimEnd('/')
                val request = Request.Builder()
                    .url("$baseUrl/api/auth/pair")
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .addHeader("Content-Type", "application/json")
                    .build()

                val response: Response = okHttpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "HTTP ${response.code}"
                    Log.e(TAG, "Pair failed: $errorBody")
                    _state.value = PairingState.Error("Ошибка сервера: $errorBody")
                    return@launch
                }

                val responseBody = response.body?.string() ?: ""
                Log.d(TAG, "Pair response: $responseBody")

                val respJson = JSONObject(responseBody)
                val token = respJson.getString("token")
                val userId = respJson.getString("userId")
                val deviceId = respJson.getString("deviceId")
                val serverPublicKeyB64 = respJson.getString("serverPublicKey")
                val serverPublicKey = android.util.Base64.decode(
                    serverPublicKeyB64,
                    android.util.Base64.NO_WRAP
                )

                // 3. Сохраняем credentials
                authRepository.saveCredentials(
                    token = token,
                    userId = userId,
                    deviceId = deviceId,
                    serverPublicKey = serverPublicKey,
                    keyPair = keyPair
                )

                // 4. Сохраняем server URL
                serverRepository.setServerUrl(serverUrl)

                // 5. Подключаемся к WebSocket
                messengerClient.connect(serverUrl, token)

                Log.i(TAG, "Pairing successful! userId=$userId, deviceId=$deviceId")
                _state.value = PairingState.Success

            } catch (e: Exception) {
                Log.e(TAG, "Pairing error", e)
                _state.value = PairingState.Error("Ошибка: ${e.message}")
            }
        }
    }

    /**
     * Выход — очистка credentials и отключение.
     */
    fun logout() {
        viewModelScope.launch {
            messengerClient.disconnect()
            authRepository.clearCredentials()
            serverRepository.clearServerUrl()
            _state.value = PairingState.Idle
        }
    }
}
