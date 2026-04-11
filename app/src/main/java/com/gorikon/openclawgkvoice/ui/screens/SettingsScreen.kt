package com.gorikon.openclawgkvoice.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gorikon.openclawgkvoice.storage.AppSettings

/**
 * Экран настроек приложения.
 *
 * Опции:
 * - Тёмная тема (toggle)
 * - Автоподключение к gateway (toggle)
 * - Язык голоса (dropdown)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onDarkThemeChanged: (Boolean) -> Unit,
    onAutoConnectChanged: (Boolean) -> Unit,
    onVoiceLanguageChanged: (String) -> Unit
) {
    val languages = listOf(
        "ru" to "Русский",
        "en" to "English",
        "de" to "Deutsch",
        "es" to "Español"
    )

    var expanded by remember { mutableStateOf(false) }

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
                title = { Text("Настройки") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Тёмная тема
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Тёмная тема", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = settings.isDarkTheme,
                    onCheckedChange = onDarkThemeChanged
                )
            }
            Divider()

            // Автоподключение
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Автоподключение к Gateway", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = settings.autoConnect,
                    onCheckedChange = onAutoConnectChanged
                )
            }
            Divider()

            // Язык голоса
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Язык голоса", style = MaterialTheme.typography.bodyLarge)
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    Text(
                        text = languages.find { it.first == settings.voiceLanguage }?.second ?: "Русский",
                        modifier = Modifier.menuAnchor(),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        languages.forEach { (code, name) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    onVoiceLanguageChanged(code)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
            Divider()

            // Версия
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = "OpenClaw GK Voice v1.0",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}
