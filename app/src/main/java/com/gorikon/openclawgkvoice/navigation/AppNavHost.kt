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
import com.gorikon.openclawgkvoice.ui.screens.AddGatewayScreen
import com.gorikon.openclawgkvoice.ui.screens.ChatScreen
import com.gorikon.openclawgkvoice.ui.screens.ChatViewModel
import com.gorikon.openclawgkvoice.ui.screens.HomeScreen
import com.gorikon.openclawgkvoice.ui.screens.HomeViewModel
import com.gorikon.openclawgkvoice.ui.screens.SettingsScreen
import com.gorikon.openclawgkvoice.ui.screens.SettingsViewModel
import com.gorikon.openclawgkvoice.ui.screens.VoiceScreen
import com.gorikon.openclawgkvoice.ui.screens.VoiceViewModel

/**
 * Корневой NavHost приложения.
 * Управляет переходами между экранами через Jetpack Compose Navigation.
 */
@Composable
fun AppNavHost() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        // Home — список gateway'ев
        composable(Screen.Home.route) {
            val viewModel: HomeViewModel = hiltViewModel()
            val gateways by viewModel.gateways.collectAsStateWithLifecycle()
            val activeGateway by viewModel.activeGateway.collectAsStateWithLifecycle()

            HomeScreen(
                gateways = gateways,
                activeGateway = activeGateway,
                onAddGateway = { navController.navigate(Screen.AddGateway.route) },
                onSettingsClick = { navController.navigate(Screen.Settings.route) },
                onSelectGateway = { gatewayId ->
                    viewModel.selectGateway(gatewayId)
                },
                onChatClick = { gatewayId ->
                    navController.navigate(Screen.Chat.createRoute(gatewayId))
                },
                onVoiceClick = { gatewayId ->
                    navController.navigate(Screen.Voice.createRoute(gatewayId))
                },
                onDeleteGateway = { gatewayId ->
                    viewModel.deleteGateway(gatewayId)
                }
            )
        }

        // Add Gateway — форма добавления
        composable(Screen.AddGateway.route) {
            // Берём HomeViewModel из родительского backStack entry для общего состояния
            val parentEntry = remember(it) { navController.getBackStackEntry(Screen.Home.route) }
            val homeViewModel: HomeViewModel = hiltViewModel(parentEntry)
            AddGatewayScreen(
                onBack = { navController.popBackStack() },
                onSave = { config ->
                    homeViewModel.addGateway(config)
                    navController.popBackStack()
                }
            )
        }

        // Chat — текстовый чат с агентом
        composable(
            route = Screen.Chat.ROUTE_PATTERN,
            arguments = listOf(navArgument("gatewayId") { type = NavType.StringType })
        ) { backStackEntry ->
            val gatewayId = backStackEntry.arguments?.getString("gatewayId") ?: return@composable
            val viewModel: ChatViewModel = hiltViewModel(
                key = gatewayId // Уникальный ключ для каждого gateway — разные ViewModel
            )

            ChatScreen(
                gatewayId = gatewayId,
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onSendMessage = { text -> viewModel.sendMessage(text) }
            )
        }

        // Voice — голосовой экран
        composable(
            route = Screen.Voice.ROUTE_PATTERN,
            arguments = listOf(navArgument("gatewayId") { type = NavType.StringType })
        ) { backStackEntry ->
            val gatewayId = backStackEntry.arguments?.getString("gatewayId") ?: return@composable
            val viewModel: VoiceViewModel = hiltViewModel(
                key = gatewayId // Уникальный ключ для каждого gateway
            )
            val voiceState by viewModel.voiceState.collectAsStateWithLifecycle()

            VoiceScreen(
                gatewayId = gatewayId,
                voiceState = voiceState,
                onBack = { navController.popBackStack() },
                onStartRecording = { viewModel.startRecording() },
                onStopRecording = { viewModel.stopRecording() },
                onPauseRecording = { viewModel.pauseRecording() }
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
