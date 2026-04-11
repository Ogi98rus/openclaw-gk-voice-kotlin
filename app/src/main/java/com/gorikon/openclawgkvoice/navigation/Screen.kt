package com.gorikon.openclawgkvoice.navigation

/**
 * Все роуты навигации приложения.
 * Используем sealed class для типобезопасной навигации.
 */
sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object AddGateway : Screen("add_gateway")
    data object Settings : Screen("settings")

    // Экраны с параметрами
    data class Chat(val gatewayId: String) : Screen("chat/$gatewayId") {
        companion object {
            const val ROUTE_PATTERN = "chat/{gatewayId}"
            fun createRoute(gatewayId: String) = "chat/$gatewayId"
        }
    }

    data class Voice(val gatewayId: String) : Screen("voice/$gatewayId") {
        companion object {
            const val ROUTE_PATTERN = "voice/{gatewayId}"
            fun createRoute(gatewayId: String) = "voice/$gatewayId"
        }
    }
}
