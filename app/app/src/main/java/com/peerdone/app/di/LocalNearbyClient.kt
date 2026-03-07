package com.peerdone.app.di

import androidx.compose.runtime.staticCompositionLocalOf
import com.peerdone.app.core.call.CallManager
import com.peerdone.app.data.DeviceIdentityStore
import com.peerdone.app.data.MeshClientRouter

val LocalNearbyClient = staticCompositionLocalOf<MeshClientRouter> {
    error("MeshClientRouter not provided. Wrap your composable with CompositionLocalProvider.")
}

val LocalDeviceIdentity = staticCompositionLocalOf<DeviceIdentityStore> {
    error("DeviceIdentityStore not provided. Wrap your composable with CompositionLocalProvider.")
}

val LocalCallManager = staticCompositionLocalOf<CallManager> {
    error("CallManager not provided. Wrap your composable with CompositionLocalProvider.")
}
