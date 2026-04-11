package com.gorikon.openclawgkvoice.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gorikon.openclawgkvoice.gateway.GatewayConfig
import java.util.UUID

/**
 * Экран добавления нового gateway'я.
 *
 * Форма с полями:
 * - Название (опционально)
 * - URL (wss://...)
 * - API Key
 *
 * При сохранении — вызывает onSave с готовым GatewayConfig.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddGatewayScreen(
    onBack: () -> Unit,
    onSave: (GatewayConfig) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var urlError by remember { mutableStateOf<String?>(null) }

    // Валидация URL: должен начинаться с ws:// или wss://
    fun validateUrl(value: String): Boolean {
        val valid = value.startsWith("ws://") || value.startsWith("wss://")
        urlError = if (!valid && value.isNotEmpty()) "URL должен начинаться с ws:// или wss://" else null
        return valid
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад"
                        )
                    }
                },
                title = { Text("Добавить Gateway") },
                actions = {
                    // Кнопка сохранения — активна только при валидном URL и API Key
                    val canSave = url.isNotBlank() && apiKey.isNotBlank() && validateUrl(url)
                    IconButton(
                        onClick = {
                            val config = GatewayConfig(
                                id = UUID.randomUUID().toString(),
                                name = name.takeIf { it.isNotBlank() } ?: "Gateway ${System.currentTimeMillis()}",
                                url = url.trim(),
                                apiKey = apiKey.trim(),
                                isActive = false
                            )
                            onSave(config)
                        },
                        enabled = canSave
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Сохранить"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Название
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Название (опционально)") },
                placeholder = { Text("Мой Gateway") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // URL
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("WebSocket URL") },
                placeholder = { Text("wss://gateway.example.com") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = urlError != null,
                supportingText = {
                    urlError?.let { Text(it) }
                }
            )

            // API Key
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                placeholder = { Text("Токен авторизации") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Подсказка
            Text(
                text = "💡 Gateway URL должен поддерживать WebSocket (ws:// или wss://). API Key выдаётся при настройке OpenClaw агента.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}
