package com.gorikon.openclawgkvoice.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gorikon.openclawgkvoice.gateway.ChatMessage
import com.gorikon.openclawgkvoice.gateway.GatewayStatus

/**
 * Экран текстового чата с агентом.
 *
 * - LazyColumn с сообщениями (пользователь справа, агент слева)
 * - TextField внизу для ввода сообщений
 * - Автоматическая прокрутка к последнему сообщению
 * - Подписка на messages через collectAsStateWithLifecycle
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    gatewayId: String,
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onSendMessage: (String) -> Unit
) {
    // Подписка на StateFlow сообщений — автоматически обновляется при изменении
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()

    // Инициализация ViewModel при первом входе на экран
    LaunchedEffect(gatewayId) {
        viewModel.initialize(gatewayId)
    }

    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Автопрокрутка к последнему сообщению
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
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
                title = { Text("Чат") },
                actions = {
                    // Индикатор статуса подключения
                    Text(
                        text = when (connectionStatus) {
                            GatewayStatus.Connected -> "🟢"
                            GatewayStatus.Connecting -> "🟡"
                            GatewayStatus.Error -> "🔴"
                            GatewayStatus.Disconnected -> "⚫"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            )
        },
        bottomBar = {
            // Поле ввода сообщения
            Surface(
                tonalElevation = 3.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Введите сообщение...") },
                        singleLine = false,
                        maxLines = 4,
                        enabled = connectionStatus == GatewayStatus.Connected
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    // Кнопка отправки
                    FloatingActionButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                onSendMessage(inputText.trim())
                                inputText = ""
                            }
                        },
                        enabled = inputText.isNotBlank() && connectionStatus == GatewayStatus.Connected
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Отправить"
                        )
                    }
                }
            }
        }
    ) { padding ->
        if (messages.isEmpty()) {
            // Placeholder — нет сообщений
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "💬",
                    style = MaterialTheme.typography.headlineLarge
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Нет сообщений",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Начните диалог с агентом",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )

                // Показываем подсказку если не подключены
                if (connectionStatus != GatewayStatus.Connected) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = when (connectionStatus) {
                            GatewayStatus.Connected -> ""
                            GatewayStatus.Connecting -> "⏳ Подключение к gateway..."
                            GatewayStatus.Error -> "❌ Ошибка подключения. Проверьте настройки."
                            GatewayStatus.Disconnected -> "⚠️ Gateway не подключён"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    ChatMessageBubble(message = message)
                }
            }
        }
    }
}

/**
 * Пузырёк сообщения (как в мессенджерах).
 */
@Composable
private fun ChatMessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isFromUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (message.isFromUser)
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                else
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
    }
}
