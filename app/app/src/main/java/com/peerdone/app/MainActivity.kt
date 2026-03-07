package com.peerdone.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.peerdone.app.ui.components.CallSignalHandler
import com.peerdone.app.data.PreferencesStore
import com.peerdone.app.di.LocalCallManager
import com.peerdone.app.di.LocalDeviceIdentity
import com.peerdone.app.di.LocalNearbyClient
import com.peerdone.app.navigation.PeerDoneNavGraph
import com.peerdone.app.navigation.Screen
import com.peerdone.app.ui.components.PermissionGate
import com.peerdone.app.ui.theme.PeerDoneBackground
import com.peerdone.app.ui.theme.PeerDoneTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as PeerDoneApplication
        val preferencesStore = PreferencesStore(this)
        val onboardingCompleted = preferencesStore.isOnboardingCompletedSync()

        setContent {
            PeerDoneTheme(darkTheme = true) {
                CompositionLocalProvider(
                    LocalNearbyClient provides app.nearbyMeshClient,
                    LocalDeviceIdentity provides app.deviceIdentityStore,
                    LocalCallManager provides app.callManager
                ) {
                    PermissionGate {
                        val navController = rememberNavController()
                        val startDestination = remember {
                            if (onboardingCompleted) Screen.Main.route else Screen.Onboarding.route
                        }

                        Box(modifier = Modifier.fillMaxSize()) {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = PeerDoneBackground
                            ) {
                                PeerDoneNavGraph(
                                    navController = navController,
                                    startDestination = startDestination
                                )
                            }
                            CallSignalHandler()
                        }
                    }
                }
            }
        }
    }
}
