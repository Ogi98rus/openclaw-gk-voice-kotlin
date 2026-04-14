package com.gorikon.openclawgkvoice.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MicExternalOn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.gorikon.openclawgkvoice.gateway.GatewayConfig
import com.gorikon.openclawgkvoice.gateway.GatewayStatus
import com.gorikon.openclawgkvoice.messenger.MessengerStatus
import com.gorikon.openclawgkvoice.ui.theme.*

/**
 * Карточка gateway'я на главном экране.
 *
 * Содержит:
 * - Название gateway'я
 * - Индикатор статуса (ConnectionStatus)
 * - URL
 * - Кнопки Voice и Chat для быстрого перехода
 * - Кнопка удаления
 */
@Composable
fun GatewayCard(
    gateway: GatewayConfig,
    isActive: Boolean,
    onSelect: () -> Unit,
    onChatClick: () -> Unit,
    onVoiceClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) SurfaceColor.copy(alpha = 0.9f) else SurfaceColor
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isActive) 4.dp else 1.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Левая часть: статус + информация
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Индикатор статуса
                ConnectionStatus(status = when (gateway.status) {
                    GatewayStatus.Connected -> MessengerStatus.Connected
                    GatewayStatus.Connecting -> MessengerStatus.Connecting
                    GatewayStatus.Error -> MessengerStatus.Error
                    GatewayStatus.Disconnected -> MessengerStatus.Disconnected
                })

                // Название и URL
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = gateway.name.takeIf { it.isNotBlank() } ?: "Безымянный Gateway",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = gateway.url.takeIf { it.isNotBlank() } ?: "Не настроен",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            // Правая часть: кнопки действий
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Кнопка Voice
                IconButton(
                    onClick = onVoiceClick,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MicExternalOn,
                        contentDescription = "Голосовой чат",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Кнопка Chat
                IconButton(
                    onClick = onChatClick,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Chat,
                        contentDescription = "Текстовый чат",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Кнопка Delete
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Удалить gateway",
                        tint = ErrorColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * Бейдж статуса подключения.
 *
 * Цветной кружок + текстовая подпись.
 */
@Composable
fun ConnectionStatus(
    status: MessengerStatus,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
    ) {
        // Цветной индикатор
        val color = when (status) {
            MessengerStatus.Connected -> ConnectedColor
            MessengerStatus.Connecting -> WarningColor
            MessengerStatus.Error -> ErrorColor
            MessengerStatus.Disconnected -> DisconnectedColor
        }

        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )

        // Текстовая подпись
        Text(
            text = when (status) {
                MessengerStatus.Connected -> "Подключён"
                MessengerStatus.Connecting -> "Подключение..."
                MessengerStatus.Error -> "Ошибка"
                MessengerStatus.Disconnected -> "Отключён"
            },
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}
