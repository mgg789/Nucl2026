package com.peerdone.app

import android.app.Application
import com.peerdone.app.core.call.CallManager
import com.peerdone.app.data.ChatHistoryStore
import com.peerdone.app.data.DeviceIdentityStore
import com.peerdone.app.data.LanMeshClient
import com.peerdone.app.data.MeshClientRouter
import com.peerdone.app.data.NearbyMeshClient
import com.peerdone.app.data.PreferencesStore
import com.peerdone.app.data.WifiDirectMeshClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class PeerDoneApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val preferencesStore: PreferencesStore by lazy { PreferencesStore(this) }

    /** Единый store истории чатов для всего приложения; очистка на экране данных отражается в чатах. */
    val chatHistoryStore: ChatHistoryStore by lazy { ChatHistoryStore(this) }

    val nearbyMeshClient: NearbyMeshClient by lazy {
        NearbyMeshClient(this, chatHistoryStore)
    }

    val wifiDirectMeshClient: WifiDirectMeshClient by lazy {
        WifiDirectMeshClient(this, chatHistoryStore)
    }

    val lanMeshClient: LanMeshClient by lazy {
        LanMeshClient(this, chatHistoryStore)
    }

    val meshClientRouter: MeshClientRouter by lazy {
        MeshClientRouter(
            scope = applicationScope,
            preferenceFlow = preferencesStore.preferredTransport,
            getPreferenceSync = { preferencesStore.preferredTransportSync() },
            nearby = nearbyMeshClient,
            wifiDirect = wifiDirectMeshClient,
            lan = lanMeshClient,
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
