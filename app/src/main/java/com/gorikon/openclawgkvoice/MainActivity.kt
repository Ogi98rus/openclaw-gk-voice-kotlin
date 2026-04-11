package com.gorikon.openclawgkvoice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.gorikon.openclawgkvoice.navigation.AppNavHost
import com.gorikon.openclawgkvoice.ui.theme.OpenClawGKVoiceTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Единственная Activity приложения.
 * Весь UI строится через Jetpack Compose, навигация управляется через AppNavHost.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // Полноэкранный режим без системных баров
        setContent {
            OpenClawGKVoiceTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavHost()
                }
            }
        }
    }
}
