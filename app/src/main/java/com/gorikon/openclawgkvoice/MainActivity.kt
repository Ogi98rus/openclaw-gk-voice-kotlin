package com.gorikon.openclawgkvoice

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.gorikon.openclawgkvoice.auth.AuthRepository
import com.gorikon.openclawgkvoice.crypto.CryptoManager
import com.gorikon.openclawgkvoice.messenger.MessengerClient
import com.gorikon.openclawgkvoice.navigation.AppNavHost
import com.gorikon.openclawgkvoice.navigation.Screen
import com.gorikon.openclawgkvoice.storage.ServerRepository
import com.gorikon.openclawgkvoice.ui.theme.OpenClawGKVoiceTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "MainActivity"

/**
 * Единственная Activity приложения.
 * Весь UI строится через Jetpack Compose, навигация управляется через AppNavHost.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var serverRepository: ServerRepository
    @Inject lateinit var cryptoManager: CryptoManager
    @Inject lateinit var messengerClient: MessengerClient

    private val activityScope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Проверяем авторизацию и определяем стартовый экран
        var startDestination: String = Screen.Home.route

        val credentials = authRepository.loadCredentials()
        if (credentials == null) {
            startDestination = Screen.Pairing.route
            Log.i(TAG, "No credentials found, starting at Pairing")
        } else {
            Log.i(TAG, "Credentials found for userId=${credentials.userId}")
            // Автоподключение к серверу
            activityScope.launch {
                val serverUrl = serverRepository.getServerUrl()
                if (serverUrl != null) {
                    Log.i(TAG, "Auto-connecting to $serverUrl")
                    messengerClient.connect(serverUrl, credentials.token)
                } else {
                    Log.w(TAG, "Server URL not saved, starting at Pairing")
                    startDestination = Screen.Pairing.route
                }
            }
        }

        val finalStartDestination = startDestination

        setContent {
            OpenClawGKVoiceTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavHost(startDestination = finalStartDestination)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.launch {
            messengerClient.disconnect()
        }
    }
}
