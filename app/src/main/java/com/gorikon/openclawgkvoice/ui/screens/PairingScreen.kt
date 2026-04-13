package com.gorikon.openclawgkvoice.ui.screens

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.zxing.integration.android.IntentIntegrator
import kotlinx.coroutines.launch

private const val TAG = "PairingScreen"

/**
 * Экран сопряжения (pairing) с Messenger Server.
 *
 * Flow:
 * 1. Пользователь вводит URL сервера
 * 2. Сканирует QR-код (или вводит код вручную)
 * 3. Нажимает "Подключиться" — идёт pairing
 * 4. При успехе — навигация на Home/Chat
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    onPairingSuccess: () -> Unit,
    viewModel: PairingViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var serverUrl by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf("Android GK Voice") }
    var pairingCode by remember { mutableStateOf("") }
    var urlError by remember { mutableStateOf<String?>(null) }

    val state by viewModel.state.collectAsState()

    // QR Scanner launcher
    val qrLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanningResult = IntentIntegrator.parseActivityResult(
                IntentIntegrator.REQUEST_CODE,
                result.resultCode,
                result.data
            )
            if (scanningResult?.contents != null) {
                val scanned = scanningResult.contents
                Log.d(TAG, "QR scanned: $scanned")

                // QR может содержать либо просто код, либо URL с кодом
                val code = if (scanned.contains("=")) {
                    // Формат: pairing_code=XXXXX
                    scanned.substringAfter("=", scanned)
                } else {
                    scanned.trim()
                }
                pairingCode = code
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Подключение к серверу") }
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
            // Заголовок
            Text(
                text = "🦊",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Text(
                text = "Введите URL Messenger Server и отсканируйте QR-код для сопряжения",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Divider()

            // URL сервера
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("URL сервера") },
                placeholder = { Text("http://192.168.1.100:3000") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = urlError != null,
                supportingText = { urlError?.let { Text(it) } }
            )

            // Имя устройства
            OutlinedTextField(
                value = deviceName,
                onValueChange = { deviceName = it },
                label = { Text("Имя устройства") },
                placeholder = { Text("Мой телефон") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Divider()

            // Кнопка сканирования QR
            Button(
                onClick = {
                    val integrator = IntentIntegrator(context as Activity)
                    integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                    integrator.setPrompt("Отсканируйте QR-код с сервера")
                    integrator.setCameraId(0)
                    integrator.setBeepEnabled(true)
                    integrator.initiateScan()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = state !is PairingState.Pairing
            ) {
                Icon(
                    imageVector = Icons.Default.QrCodeScanner,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Сканировать QR-код")
            }

            // Или ввести код вручную
            OutlinedTextField(
                value = pairingCode,
                onValueChange = { pairingCode = it },
                label = { Text("Код сопряжения (или отсканируйте)") },
                placeholder = { Text("XXXXXX") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.weight(1f))

            // Статус
            when (val s = state) {
                is PairingState.Error -> {
                    Text(
                        text = "❌ ${s.message}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                is PairingState.Pairing -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                    Text(
                        text = "Подключение...",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                else -> {}
            }

            // Кнопка подключения
            Button(
                onClick = {
                    // Валидация URL
                    urlError = when {
                        serverUrl.isBlank() -> "Введите URL сервера"
                        !serverUrl.startsWith("http://") && !serverUrl.startsWith("https://") ->
                            "URL должен начинаться с http:// или https://"
                        else -> null
                    }
                    if (urlError != null) return@Button

                    if (pairingCode.isBlank()) {
                        // Просто проверяем connection без pairing
                        return@Button
                    }

                    scope.launch {
                        viewModel.pair(
                            serverUrl = serverUrl.trim(),
                            deviceName = deviceName.takeIf { it.isNotBlank() } ?: "Android GK Voice",
                            pairingCode = pairingCode.trim()
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = serverUrl.isNotBlank() && state !is PairingState.Pairing
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Подключиться")
            }
        }
    }

    // Переход на главный экран при успехе
    LaunchedEffect(state) {
        if (state is PairingState.Success) {
            onPairingSuccess()
        }
    }
}
