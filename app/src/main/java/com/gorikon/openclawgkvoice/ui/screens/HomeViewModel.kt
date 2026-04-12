package com.gorikon.openclawgkvoice.ui.screens

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gorikon.openclawgkvoice.gateway.GatewayCallback
import com.gorikon.openclawgkvoice.gateway.GatewayConfig
import com.gorikon.openclawgkvoice.gateway.GatewayManager
import com.gorikon.openclawgkvoice.gateway.GatewayStatus
import com.gorikon.openclawgkvoice.storage.GatewayRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "HomeViewModel"

/**
 * ViewModel главного экрана — управление списком gateway'ев.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val gatewayManager: GatewayManager,
    private val gatewayRepository: GatewayRepository
) : ViewModel() {

    val gateways: StateFlow<List<GatewayConfig>> = gatewayManager.gateways
    val activeGateway: StateFlow<GatewayConfig?> = gatewayManager.activeGateway

    // Gateway callback для обработки событий подключения
    private val gatewayCallback = object : GatewayCallback {
        override fun onConnected() {}
        override fun onDisconnected(code: Int, reason: String) {}
        override fun onMessage(text: String) {}
        override fun onAudio(data: ByteArray) {}
        override fun onError(error: Throwable) {}
        override fun onStatusChanged(status: GatewayStatus) {
            // Обновляем статус в менеджере
            gatewayManager.activeGateway.value?.let { gw ->
                gatewayManager.updateGatewayStatus(gw.id, status)
                // Также сохраняем в репозиторий
                viewModelScope.launch {
                    gatewayRepository.updateGatewayStatus(gw.id, status)
                }
            }
        }
    }

    init {
        Log.d(TAG, "HomeViewModel init — loading gateways")
        // Загружаем сохранённые gateway'и при старте
        viewModelScope.launch {
            try {
                val saved = gatewayRepository.getGateways()
                Log.d(TAG, "Loaded ${saved.size} gateway(s): ${saved.map { it.name }}")
                gatewayManager.loadGateways(saved)

                // Если есть активный gateway — автоматически подключаемся
                val active = saved.find { it.isActive }
                if (active != null) {
                    Log.d(TAG, "Auto-connecting to active: ${active.name} (${active.url})")
                    gatewayManager.selectGateway(active.id, gatewayCallback)
                } else {
                    Log.d(TAG, "No active gateway found")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading gateways", e)
            }
        }
    }

    /**
     * Выбрать gateway как активный и подключиться.
     */
    fun selectGateway(gatewayId: String) {
        Log.d(TAG, "selectGateway called: $gatewayId")
        gatewayManager.selectGateway(gatewayId, gatewayCallback)
    }

    /**
     * Удалить gateway из списка.
     */
    fun deleteGateway(gatewayId: String) {
        viewModelScope.launch {
            gatewayRepository.deleteGateway(gatewayId)
            gatewayManager.removeGateway(gatewayId)
        }
    }

    /**
     * Добавить новый gateway (вызывается из AddGatewayScreen).
     */
    fun addGateway(config: GatewayConfig) {
        Log.d(TAG, "addGateway called: ${config.name} url=${config.url}")
        viewModelScope.launch {
            try {
                // Сначала добавляем в менеджере — он может пометить первый gateway как isActive
                gatewayManager.addGateway(config)

                // Получаем фактический конфиг (с потенциально изменённым isActive)
                val addedGateway = gateways.value.find { it.id == config.id } ?: config
                Log.d(TAG, "Added gateway, isActive=${addedGateway.isActive}")

                // Сохраняем в репозиторий с актуальным isActive
                gatewayRepository.saveGateway(addedGateway)
                Log.d(TAG, "Saved gateway to repository")

                // Если gateway стал активным — подключаемся
                if (addedGateway.isActive) {
                    Log.d(TAG, "Auto-selecting gateway: ${config.id}")
                    gatewayManager.selectGateway(config.id, gatewayCallback)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error adding gateway", e)
            }
        }
    }
}
