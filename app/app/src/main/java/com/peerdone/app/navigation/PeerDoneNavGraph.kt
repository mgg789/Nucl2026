package com.peerdone.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.peerdone.app.di.LocalCallManager
import com.peerdone.app.ui.screens.ActiveCallScreen
import com.peerdone.app.ui.screens.CallsScreen
import com.peerdone.app.ui.screens.ChatListScreen
import com.peerdone.app.ui.screens.ChatScreen
import com.peerdone.app.ui.screens.MainScreen
import com.peerdone.app.ui.screens.DataScreen
import com.peerdone.app.ui.screens.NetworkScreen
import com.peerdone.app.ui.screens.SettingsScreen
import com.peerdone.app.ui.onboarding.OnboardingScreen
import com.peerdone.app.ui.onboarding.PeerDiscoveryScreen
import com.peerdone.app.ui.onboarding.ProfileSetupScreen

@Composable
fun PeerDoneNavGraph(
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        composable(Screen.Onboarding.route) {
            OnboardingScreen(
                onComplete = {
                    navController.navigate(Screen.ProfileSetup.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.ProfileSetup.route) {
            ProfileSetupScreen(
                onComplete = {
                    navController.navigate(Screen.PeerDiscovery.route) {
                        popUpTo(Screen.ProfileSetup.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.PeerDiscovery.route) {
            PeerDiscoveryScreen(
                onComplete = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.PeerDiscovery.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Main.route) {
            MainScreen(navController = navController)
        }

        composable(Screen.ChatList.route) {
            ChatListScreen(
                onChatClick = { peerId ->
                    navController.navigate(Screen.Chat.createRoute(peerId))
                }
            )
        }

        composable(
            route = Screen.Chat.route,
            arguments = listOf(navArgument("peerId") { type = NavType.StringType })
        ) { backStackEntry ->
            val peerId = backStackEntry.arguments?.getString("peerId") ?: return@composable
            ChatScreen(
                peerId = peerId,
                onBack = { navController.popBackStack() },
                onCallClick = { navController.navigate(Screen.ActiveCall.createRoute(peerId)) }
            )
        }

        composable(Screen.Calls.route) {
            CallsScreen(
                onCallClick = { peerId ->
                    navController.navigate(Screen.ActiveCall.createRoute(peerId))
                }
            )
        }

        composable(
            route = Screen.ActiveCall.route,
            arguments = listOf(navArgument("peerId") { type = NavType.StringType })
        ) { backStackEntry ->
            val peerId = backStackEntry.arguments?.getString("peerId") ?: return@composable
            val callManager = LocalCallManager.current
            LaunchedEffect(peerId) {
                if (callManager.activeCall.value?.peerId != peerId) {
                    callManager.initiateCall(peerId, peerId.take(16))
                }
            }
            ActiveCallScreen(
                callManager = callManager,
                onCallEnded = { navController.popBackStack() }
            )
        }

        composable(Screen.Network.route) {
            NetworkScreen()
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onProfileClick = { navController.navigate(Screen.Profile.route) },
                onNetworkClick = { navController.navigate(Screen.Network.route) },
                onDataClick = { navController.navigate(Screen.Data.route) }
            )
        }

        composable(Screen.Data.route) {
            DataScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.Profile.route) {
            ProfileSetupScreen(
                onComplete = { navController.popBackStack() },
                isEditing = true
            )
        }
    }
}
