package com.peerdone.app

import android.app.Application
import com.peerdone.app.core.call.CallManager
import com.peerdone.app.data.DeviceIdentityStore
import com.peerdone.app.data.NearbyMeshClient

class PeerDoneApplication : Application() {

    val nearbyMeshClient: NearbyMeshClient by lazy {
        NearbyMeshClient(this)
    }

    val deviceIdentityStore: DeviceIdentityStore by lazy {
        DeviceIdentityStore(this)
    }

    val callManager: CallManager by lazy {
        CallManager(this, nearbyMeshClient, deviceIdentityStore.getOrCreate())
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
