package com.gorikon.openclawgkvoice.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gorikon.openclawgkvoice.messenger.Conversation
import com.gorikon.openclawgkvoice.messenger.MessengerStatus

/**
 * Главный экран — список конверсаций (диалогов).
 *
 * - Пустой список → placeholder с кнопкой добавления
 * - Есть конверсации → LazyColumn с карточками
 * - FAB для создания нового диалога
 * - Иконка настроек в App Bar
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    conversations: List<Conversation>,
    connectionStatus: MessengerStatus,
    onCreateConversation: (String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onChatClick: (String) -> Unit,
    onVoiceClick: (String) -> Unit,
    onSettingsClick: () -> Unit,
    onLogout: () -> Unit
) {
    var showNewDialog by remember { mutableStateOf(false) }

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
                    // Индикатор статуса подключения
                    Text(
                        text = when (connectionStatus) {
                            MessengerStatus.Connected -> "🟢"
                            MessengerStatus.Connecting -> "🟡"
                            MessengerStatus.Error -> "🔴"
                            MessengerStatus.Disconnected -> "⚫"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Настройки"
                        )
                    }
                    IconButton(onClick = onLogout) {
                        Icon(
                            imageVector = Icons.Default.Exit,
                            contentDescription = "Выход"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewDialog = true }) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Новый диалог"
                )
            }
        }
    ) { padding ->
        if (conversations.isEmpty()) {
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
                    text = "💬",
                    style = MaterialTheme.typography.headlineLarge
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Нет диалогов",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Нажмите + чтобы создать первый диалог",
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
                items(conversations, key = { it.id }) { conversation ->
                    ConversationCard(
                        conversation = conversation,
                        onChatClick = { onChatClick(conversation.id) },
                        onVoiceClick = { onVoiceClick(conversation.id) },
                        onDelete = { onDeleteConversation(conversation.id) }
                    )
                }
            }
        }

        // Диалог создания нового диалога
        if (showNewDialog) {
            NewConversationDialog(
                onDismiss = { showNewDialog = false },
                onCreate = { title ->
                    onCreateConversation(title)
                    showNewDialog = false
                }
            )
        }
    }
}

/**
 * Карточка конверсации.
 */
@Composable
private fun ConversationCard(
    conversation: Conversation,
    onChatClick: () -> Unit,
    onVoiceClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = conversation.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = conversation.id.take(8),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(onClick = onVoiceClick, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Голосовой чат",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onChatClick, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.Chat,
                        contentDescription = "Текстовый чат",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Удалить диалог",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * Диалог создания новой конверсации.
 */
@Composable
private fun NewConversationDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый диалог") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Название") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (title.isNotBlank()) onCreate(title.trim()) },
                enabled = title.isNotBlank()
            ) { Text("Создать") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}
