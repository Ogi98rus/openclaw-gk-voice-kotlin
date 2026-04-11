package com.gorikon.openclawgkvoice.gateway

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Менеджер управления несколькими gateway-подключениями.
 *
 * Хранит список всех gateway'ев, управляет активным подключением
 * и предоставляет реактивный StateFlow для UI.
 */
@Singleton
class GatewayManager @Inject constructor(
    private val gatewayClient: GatewayClient
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    // Реактивный список всех gateway'ев
    private val _gateways = MutableStateFlow<List<GatewayConfig>>(emptyList())
    val gateways: StateFlow<List<GatewayConfig>> = _gateways.asStateFlow()

    // Активный (выбранный) gateway
    private val _activeGateway = MutableStateFlow<GatewayConfig?>(null)
    val activeGateway: StateFlow<GatewayConfig?> = _activeGateway.asStateFlow()

    init {
        // При изменении списка обновляем активный gateway
        scope.launch {
            gateways.collect { list ->
                val active = list.find { it.isActive }
                _activeGateway.value = active
            }
        }
    }

    /**
     * Добавить новый gateway в список.
     */
    fun addGateway(config: GatewayConfig) {
        // Если это первый gateway — делаем его активным
        val gatewayToAdd = if (_gateways.value.isEmpty()) {
            config.copy(isActive = true)
        } else {
            config
        }

        _gateways.update { currentList ->
            currentList + gatewayToAdd
        }
    }

    /**
     * Удалить gateway по ID.
     * Если удаляемый gateway был активным — сбрасываем активный.
     */
    fun removeGateway(gatewayId: String) {
        val wasActive = _gateways.value.find { it.id == gatewayId }?.isActive == true

        _gateways.update { currentList ->
            currentList.filter { it.id != gatewayId }
        }

        // Если удалили активный gateway — отключаем клиент
        if (wasActive) {
            gatewayClient.disconnect()
        }
    }

    /**
     * Выбрать gateway как активный.
     * Подключается к новому gateway'ю и отключается от старого.
     */
    fun selectGateway(gatewayId: String, callback: GatewayCallback) {
        val gateway = _gateways.value.find { it.id == gatewayId } ?: return

        // Обновляем isActive у всех gateway'ев
        _gateways.update { currentList ->
            currentList.map { gw ->
                gw.copy(isActive = gw.id == gatewayId)
            }
        }

        // Отключаемся от текущего и подключаемся к новому
        gatewayClient.disconnect()
        gatewayClient.connect(gateway, callback)
    }

    /**
     * Обновить статус gateway'я (вызывается из GatewayClient callback'ов).
     */
    fun updateGatewayStatus(gatewayId: String, status: GatewayStatus) {
        _gateways.update { currentList ->
            currentList.map { gw ->
                if (gw.id == gatewayId) gw.copy(status = status) else gw
            }
        }
    }

    /**
     * Получить активный gateway (синхронно).
     */
    fun getActiveGateway(): GatewayConfig? = _activeGateway.value

    /**
     * Загрузить сохранённый список gateway'ев (вызывается из Repository при старте).
     */
    fun loadGateways(gateways: List<GatewayConfig>) {
        _gateways.value = gateways
    }

    /**
     * Получить gateway по ID.
     */
    fun getGatewayById(gatewayId: String): GatewayConfig? =
        _gateways.value.find { it.id == gatewayId }
}
