package com.gorikon.openclawgkvoice.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gorikon.openclawgkvoice.gateway.GatewayStatus
import com.gorikon.openclawgkvoice.gateway.VoiceState
import com.gorikon.openclawgkvoice.ui.components.ConnectionStatus
import com.gorikon.openclawgkvoice.ui.components.SimpleAmplitudeBar
import com.gorikon.openclawgkvoice.ui.theme.ConnectedColor
import com.gorikon.openclawgkvoice.ui.theme.ErrorColor
import com.gorikon.openclawgkvoice.ui.theme.WarningColor

/**
 * Экран голосовой связи с агентом.
 *
 * - Большая круглая кнопка записи (press & hold)
 * - Визуализация амплитуды (SimpleAmplitudeBar)
 * - Статус подключения
 * - Свайп вверх для отмены записи
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceScreen(
    gatewayId: String,
    voiceState: VoiceState,
    onBack: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onPauseRecording: () -> Unit
) {
    var isDraggingUp by remember { mutableStateOf(false) }

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
                title = { Text("Голос") },
                actions = {
                    ConnectionStatus(status = voiceState.connectionStatus)
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            // Визуализация амплитуды
            SimpleAmplitudeBar(
                amplitude = voiceState.amplitude,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
            )

            // Статус-текст
            Text(
                text = voiceState.statusText,
                style = MaterialTheme.typography.titleLarge,
                color = when {
                    voiceState.isRecording -> WarningColor
                    voiceState.isPlaying -> ConnectedColor
                    voiceState.connectionStatus == GatewayStatus.Error -> ErrorColor
                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                },
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Большая кнопка записи (press & hold + swipe up для отмены)
            Box(
                modifier = Modifier
                    .size(180.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { _ ->
                                onStartRecording()
                                isDraggingUp = false
                            },
                            onDrag = { change: androidx.compose.ui.input.pointer.PointerInputChange, dragAmount: androidx.compose.ui.geometry.Offset ->
                                // Если свайп вверх > 50px — отменяем
                                if (dragAmount.y < -50f) {
                                    isDraggingUp = true
                                }
                            },
                            onDragEnd = {
                                if (isDraggingUp) {
                                    // Отмена записи (можно добавить логику отмены)
                                }
                                onStopRecording()
                                isDraggingUp = false
                            },
                            onDragCancel = {
                                onStopRecording()
                                isDraggingUp = false
                            }
                        ) { _, _ -> }
                    }
                    .clip(CircleShape)
                    .background(
                        if (voiceState.isRecording)
                            WarningColor.copy(alpha = 0.3f)
                        else
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                // Внутренний круг
                Box(
                    modifier = Modifier
                        .size(if (voiceState.isRecording) 120.dp else 100.dp)
                        .clip(CircleShape)
                        .background(
                            if (voiceState.isRecording)
                                WarningColor
                            else
                                MaterialTheme.colorScheme.primary
                        )
                )

                // Иконка микрофона
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.Mic,
                    contentDescription = if (voiceState.isRecording) "Остановить запись" else "Начать запись",
                    tint = if (voiceState.isRecording)
                        MaterialTheme.colorScheme.background
                    else
                        MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(48.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Подсказка
            Text(
                text = if (voiceState.isRecording)
                    "Отпустите для отправки, свайп вверх для отмены"
                else
                    "Нажмите и удерживайте для записи",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
