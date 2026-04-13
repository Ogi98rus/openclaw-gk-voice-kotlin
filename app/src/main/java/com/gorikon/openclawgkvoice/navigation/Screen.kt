package com.gorikon.openclawgkvoice.navigation

/**
 * Все роуты навигации приложения.
 * Используем sealed class для типобезопасной навигации.
 */
sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Pairing : Screen("pairing")
    data object Settings : Screen("settings")

    // Экраны с параметрами
    data class Chat(val conversationId: String) : Screen("chat/$conversationId") {
        companion object {
            const val ROUTE_PATTERN = "chat/{conversationId}"
            fun createRoute(conversationId: String) = "chat/$conversationId"
        }
    }

    data class Voice(val conversationId: String) : Screen("voice/$conversationId") {
        companion object {
            const val ROUTE_PATTERN = "voice/{conversationId}"
            fun createRoute(conversationId: String) = "voice/$conversationId"
        }
    }
}
