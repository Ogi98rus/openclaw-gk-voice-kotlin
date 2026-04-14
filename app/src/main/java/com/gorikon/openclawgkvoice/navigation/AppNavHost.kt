package com.gorikon.openclawgkvoice.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gorikon.openclawgkvoice.ui.screens.ChatScreen
import com.gorikon.openclawgkvoice.ui.screens.ChatViewModel
import com.gorikon.openclawgkvoice.ui.screens.HomeScreen
import com.gorikon.openclawgkvoice.ui.screens.HomeViewModel
import com.gorikon.openclawgkvoice.ui.screens.PairingScreen
import com.gorikon.openclawgkvoice.ui.screens.SettingsScreen
import com.gorikon.openclawgkvoice.ui.screens.SettingsViewModel
import com.gorikon.openclawgkvoice.ui.screens.VoiceScreen
import com.gorikon.openclawgkvoice.ui.screens.VoiceViewModel

/**
 * Корневой NavHost приложения.
 * Управляет переходами между экранами через Jetpack Compose Navigation.
 */
@Composable
fun AppNavHost(
    startDestination: String = Screen.Home.route
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        // Home — список конверсаций
        composable(Screen.Home.route) {
            val viewModel: HomeViewModel = hiltViewModel()
            val conversations by viewModel.conversations.collectAsStateWithLifecycle()
            val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()

            HomeScreen(
                conversations = conversations,
                connectionStatus = connectionStatus,
                onCreateConversation = { title -> viewModel.createConversation(title) },
                onDeleteConversation = { id -> viewModel.deleteConversation(id) },
                onChatClick = { conversationId ->
                    navController.navigate(Screen.Chat.createRoute(conversationId))
                },
                onVoiceClick = { conversationId ->
                    navController.navigate(Screen.Voice.createRoute(conversationId))
                },
                onSettingsClick = { navController.navigate(Screen.Settings.route) },
                onLogout = {
                    viewModel.logout()
                    navController.navigate(Screen.Pairing.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                }
            )
        }

        // Pairing — экран сопряжения
        composable(Screen.Pairing.route) {
            PairingScreen(
                onPairingSuccess = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Pairing.route) { inclusive = true }
                    }
                }
            )
        }

        // Chat — текстовый чат
        composable(
            route = Screen.Chat.ROUTE_PATTERN,
            arguments = listOf(navArgument("conversationId") { type = NavType.StringType })
        ) { backStackEntry ->
            val conversationId = backStackEntry.arguments?.getString("conversationId") ?: return@composable
            val viewModel: ChatViewModel = hiltViewModel(key = conversationId)

            ChatScreen(
                conversationId = conversationId,
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onSendMessage = { text -> viewModel.sendMessage(text) }
            )
        }

        // Voice — голосовой экран
        composable(
            route = Screen.Voice.ROUTE_PATTERN,
            arguments = listOf(navArgument("conversationId") { type = NavType.StringType })
        ) { backStackEntry ->
            val conversationId = backStackEntry.arguments?.getString("conversationId") ?: return@composable
            val viewModel: VoiceViewModel = hiltViewModel(key = conversationId)

            VoiceScreen(
                conversationId = conversationId,
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }

        // Settings — настройки
        composable(Screen.Settings.route) {
            val viewModel: SettingsViewModel = hiltViewModel()
            val settings by viewModel.settings.collectAsStateWithLifecycle()

            SettingsScreen(
                settings = settings,
                onBack = { navController.popBackStack() },
                onDarkThemeChanged = { viewModel.setDarkTheme(it) },
                onAutoConnectChanged = { viewModel.setAutoConnect(it) },
                onVoiceLanguageChanged = { viewModel.setVoiceLanguage(it) }
            )
        }
    }
}
