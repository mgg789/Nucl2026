package com.peerdone.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import com.peerdone.app.di.LocalCallManager
import com.peerdone.app.di.LocalDeviceIdentity
import com.peerdone.app.di.LocalNearbyClient
import com.peerdone.app.navigation.BottomNavItem
import com.peerdone.app.navigation.Screen
import com.peerdone.app.ui.components.PeerDoneBottomNavBar

@Composable
fun MainScreen(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    val nearbyClient = LocalNearbyClient.current
    val identityStore = LocalDeviceIdentity.current
    val callManager = LocalCallManager.current
    val localIdentity = remember { identityStore.getOrCreate() }
    val incomingCall by callManager.incomingCall.collectAsState()
    
    DisposableEffect(Unit) {
        if (!nearbyClient.isRunning.value) {
            nearbyClient.start(localIdentity)
        }
        onDispose {
            nearbyClient.stop()
        }
    }
    
    var selectedTab by remember { mutableStateOf(BottomNavItem.CHATS) }
    var selectedChatPeerId by remember { mutableStateOf<String?>(null) }

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
                        onProfileClick = { }
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
