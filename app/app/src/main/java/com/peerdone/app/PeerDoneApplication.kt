package com.peerdone.app

import android.app.Application
import com.peerdone.app.core.call.CallManager
import com.peerdone.app.data.DeviceIdentityStore
import com.peerdone.app.data.LanMeshClient
import com.peerdone.app.data.MeshClientRouter
import com.peerdone.app.data.MultiTransportMeshClient
import com.peerdone.app.data.NearbyMeshClient
import com.peerdone.app.data.PreferencesStore
import com.peerdone.app.data.WifiDirectMeshClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class PeerDoneApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val preferencesStore: PreferencesStore by lazy { PreferencesStore(this) }

    val nearbyMeshClient: NearbyMeshClient by lazy {
        NearbyMeshClient(this)
    }

    val wifiDirectMeshClient: WifiDirectMeshClient by lazy {
        WifiDirectMeshClient(this)
    }

    val lanMeshClient: LanMeshClient by lazy {
        LanMeshClient(this)
    }

    val multiTransportMeshClient: MultiTransportMeshClient by lazy {
        MultiTransportMeshClient(
            scope = applicationScope,
            nearby = nearbyMeshClient,
            wifiDirect = wifiDirectMeshClient,
            lan = lanMeshClient,
        )
    }

    val meshClientRouter: MeshClientRouter by lazy {
        MeshClientRouter(
            scope = applicationScope,
            preferenceFlow = preferencesStore.preferredTransport,
            getPreferenceSync = { preferencesStore.preferredTransportSync() },
            nearby = nearbyMeshClient,
            wifiDirect = wifiDirectMeshClient,
            lan = lanMeshClient,
            multi = multiTransportMeshClient,
        )
    }

    val deviceIdentityStore: DeviceIdentityStore by lazy {
        DeviceIdentityStore(this)
    }

    val callManager: CallManager by lazy {
        CallManager(this, meshClientRouter, deviceIdentityStore.getOrCreate())
    }

    companion object {
        private lateinit var instance: PeerDoneApplication

        fun getInstance(): PeerDoneApplication = instance
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
