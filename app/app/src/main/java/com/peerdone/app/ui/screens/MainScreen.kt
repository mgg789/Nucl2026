package com.peerdone.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import com.peerdone.app.data.PreferencesStore
import com.peerdone.app.di.LocalCallManager
import com.peerdone.app.di.LocalDeviceIdentity
import com.peerdone.app.di.LocalNearbyClient
import com.peerdone.app.navigation.BottomNavItem
import com.peerdone.app.navigation.Screen
import com.peerdone.app.ui.components.PeerDoneBottomNavBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@Composable
fun MainScreen(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val nearbyClient = LocalNearbyClient.current
    val identityStore = LocalDeviceIdentity.current
    val callManager = LocalCallManager.current
    val localIdentity = remember { identityStore.getOrCreate() }
    val incomingCall by callManager.incomingCall.collectAsState()
    var displayName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val prefs = PreferencesStore(context)
        displayName = withContext(Dispatchers.IO) {
            listOf(
                prefs.userFirstName.first(),
                prefs.userLastName.first(),
                prefs.userNickname.first()
            ).filter { it.isNotBlank() }.joinToString(" ").take(30).ifBlank { null }
        }
    }

    var selectedTab by remember { mutableStateOf(BottomNavItem.CHATS) }
    var selectedChatPeerId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedTab) {
        if (selectedTab == BottomNavItem.NETWORK || selectedTab == BottomNavItem.SETTINGS) {
            val prefs = PreferencesStore(context)
            displayName = withContext(Dispatchers.IO) {
                listOf(
                    prefs.userFirstName.first(),
                    prefs.userLastName.first(),
                    prefs.userNickname.first()
                ).filter { it.isNotBlank() }.joinToString(" ").take(30).ifBlank { null }
            }
        }
    }

    DisposableEffect(Unit) {
        if (!nearbyClient.isRunning.value) {
            nearbyClient.start(localIdentity, null)
        }
        onDispose {
            nearbyClient.stop()
        }
    }

    // Не перезапускаем сеть при загрузке displayName — это обрывало подключение.
    // Имя в сети обновится при следующем запуске приложения.
    
    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            if (selectedChatPeerId == null) {
                PeerDoneBottomNavBar(
                    selectedItem = selectedTab,
                    onItemSelected = { selectedTab = it }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                BottomNavItem.CHATS -> {
                    val chatPeerId = selectedChatPeerId
                    if (chatPeerId == null) {
                        ChatListScreen(
                            onChatClick = { selectedChatPeerId = it }
                        )
                    } else {
                        ChatScreen(
                            peerId = chatPeerId,
                            onBack = { selectedChatPeerId = null },
                            onCallClick = { navController.navigate(Screen.ActiveCall.createRoute(chatPeerId)) }
                        )
                    }
                }
                BottomNavItem.CALLS -> {
                    CallsScreen(
                        onCallClick = { peerId ->
                            navController.navigate(Screen.ActiveCall.createRoute(peerId))
                        }
                    )
                }
                BottomNavItem.NETWORK -> {
                    NetworkScreen()
                }
                BottomNavItem.SETTINGS -> {
                    SettingsScreen(
                        onProfileClick = { navController.navigate(Screen.Profile.route) },
                        onNetworkClick = { navController.navigate(Screen.Network.route) },
                        onDataClick = { navController.navigate(Screen.Data.route) }
                    )
                }
            }
            
            incomingCall?.let { incoming ->
                IncomingCallScreen(
                    callerName = incoming.peerName,
                    onAccept = {
                        callManager.acceptCall()
                        navController.navigate(Screen.ActiveCall.createRoute(incoming.peerId))
                    },
                    onDecline = { callManager.declineCall() }
                )
            }
        }
    }
}
