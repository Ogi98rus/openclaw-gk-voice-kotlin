package com.gorikon.openclawgkvoice.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gorikon.openclawgkvoice.gateway.GatewayConfig
import com.gorikon.openclawgkvoice.ui.components.GatewayCard

/**
 * Главный экран — список всех gateway'ев.
 *
 * - Пустой список → показывает placeholder с кнопкой добавления
 * - Есть gateway'и → LazyColumn с карточками
 * - FAB для добавления нового gateway'я
 * - Иконка настроек в App Bar
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    gateways: List<GatewayConfig>,
    activeGateway: GatewayConfig?,
    onAddGateway: () -> Unit,
    onSettingsClick: () -> Unit,
    onSelectGateway: (String) -> Unit,
    onChatClick: (String) -> Unit,
    onVoiceClick: (String) -> Unit,
    onDeleteGateway: (String) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "OpenClaw GK Voice",
                        style = MaterialTheme.typography.headlineMedium
                    )
                },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Настройки"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddGateway) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Добавить gateway"
                )
            }
        }
    ) { padding ->
        if (gateways.isEmpty()) {
            // Placeholder для пустого списка
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "🦊",
                    style = MaterialTheme.typography.headlineLarge
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Нет подключённых gateway'ев",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Нажмите + чтобы добавить первый gateway",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(gateways, key = { it.id }) { gateway ->
                    GatewayCard(
                        gateway = gateway,
                        isActive = gateway.isActive,
                        onSelect = { onSelectGateway(gateway.id) },
                        onChatClick = { onChatClick(gateway.id) },
                        onVoiceClick = { onVoiceClick(gateway.id) },
                        onDelete = { onDeleteGateway(gateway.id) }
                    )
                }
            }
        }
    }
}
