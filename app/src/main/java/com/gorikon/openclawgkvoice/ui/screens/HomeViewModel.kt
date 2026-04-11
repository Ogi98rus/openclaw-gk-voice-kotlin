package com.gorikon.openclawgkvoice.ui.screens

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
        // Загружаем сохранённые gateway'и при старте
        viewModelScope.launch {
            val saved = gatewayRepository.getGateways()
            gatewayManager.loadGateways(saved)

            // Если есть активный gateway — автоматически подключаемся
            val active = saved.find { it.isActive }
            if (active != null) {
                gatewayManager.selectGateway(active.id, gatewayCallback)
            }
        }
    }

    /**
     * Выбрать gateway как активный и подключиться.
     */
    fun selectGateway(gatewayId: String) {
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
        viewModelScope.launch {
            gatewayRepository.saveGateway(config)
            gatewayManager.addGateway(config)
        }
    }
}
