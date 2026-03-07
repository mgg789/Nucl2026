package com.peerdone.app.data

import com.peerdone.app.core.message.OutboundContent
import com.peerdone.app.core.transport.TransportHealth
import com.peerdone.app.domain.AccessPolicy
import com.peerdone.app.domain.LocalIdentity
import com.peerdone.app.domain.MeshEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Роутер выбора транспорта: при смене предпочтения (auto/nearby/wifi_direct)
 * останавливает текущий клиент и запускает другой с теми же identity/displayName.
 */
class MeshClientRouter(
    private val scope: CoroutineScope,
    private val preferenceFlow: kotlinx.coroutines.flow.Flow<String>,
    private val getPreferenceSync: () -> String,
    private val nearby: NearbyMeshClient,
    private val wifiDirect: WifiDirectMeshClient,
) {
    private var current: Any = nearby
    private var lastIdentity: LocalIdentity? = null
    private var lastDisplayName: String? = null

    private fun active(): Any = current

    init {
        scope.launch {
            preferenceFlow.collect { pref ->
                val next = if (pref == "wifi_direct") wifiDirect else nearby
                if (next != current) {
                    (current as? NearbyMeshClient)?.stop()
                    (current as? WifiDirectMeshClient)?.stop()
                    current = next
                    lastIdentity?.let { id ->
                        lastDisplayName.let { name ->
                            when (next) {
                                is NearbyMeshClient -> next.start(id, name)
                                is WifiDirectMeshClient -> next.start(id, name)
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }
    }

    val isRunning: StateFlow<Boolean>
        get() = when (val a = active()) {
            is NearbyMeshClient -> a.isRunning
            is WifiDirectMeshClient -> a.isRunning
            else -> MutableStateFlow(false).asStateFlow()
        }

    val connectedPeerInfos: StateFlow<List<NearbyMeshClient.PeerInfo>>
        get() = when (val a = active()) {
            is NearbyMeshClient -> a.connectedPeerInfos
            is WifiDirectMeshClient -> a.connectedPeerInfos
            else -> MutableStateFlow(emptyList<NearbyMeshClient.PeerInfo>()).asStateFlow()
        }

    val incomingMessages: StateFlow<List<ReceivedMeshMessage>>
        get() = when (val a = active()) {
            is NearbyMeshClient -> a.incomingMessages
            is WifiDirectMeshClient -> a.incomingMessages
            else -> MutableStateFlow(emptyList<ReceivedMeshMessage>()).asStateFlow()
        }

    val realtimeMessages: StateFlow<List<ReceivedMeshMessage>>
        get() = when (val a = active()) {
            is NearbyMeshClient -> a.realtimeMessages
            is WifiDirectMeshClient -> a.realtimeMessages
            else -> MutableStateFlow(emptyList<ReceivedMeshMessage>()).asStateFlow()
        }

    val topology: StateFlow<TopologySnapshot>
        get() = when (val a = active()) {
            is NearbyMeshClient -> a.topology
            is WifiDirectMeshClient -> a.topology
            else -> MutableStateFlow(TopologySnapshot()).asStateFlow()
        }

    val deliveryMetrics: StateFlow<DeliveryMetrics>
        get() = when (val a = active()) {
            is NearbyMeshClient -> a.deliveryMetrics
            is WifiDirectMeshClient -> a.deliveryMetrics
            else -> MutableStateFlow(DeliveryMetrics()).asStateFlow()
        }

    val chatHistoryStore: ChatHistoryStore
        get() = when (val a = active()) {
            is NearbyMeshClient -> a.chatHistoryStore
            is WifiDirectMeshClient -> a.chatHistoryStore
            else -> throw IllegalStateException("No active client")
        }

    val connectedEndpoints: StateFlow<List<String>>
        get() = when (val a = active()) {
            is NearbyMeshClient -> a.connectedEndpoints
            is WifiDirectMeshClient -> a.connectedEndpoints
            else -> MutableStateFlow(emptyList<String>()).asStateFlow()
        }

    val stats: StateFlow<MeshStats>
        get() = when (val a = active()) {
            is NearbyMeshClient -> a.stats
            is WifiDirectMeshClient -> a.stats
            else -> MutableStateFlow(MeshStats()).asStateFlow()
        }

    val testConfig: StateFlow<NetworkTestConfig>
        get() = when (val a = active()) {
            is NearbyMeshClient -> a.testConfig
            is WifiDirectMeshClient -> a.testConfig
            else -> MutableStateFlow(NetworkTestConfig()).asStateFlow()
        }

    val outboundRecords: StateFlow<List<OutboundMessageRecord>>
        get() = when (val a = active()) {
            is NearbyMeshClient -> a.outboundRecords
            is WifiDirectMeshClient -> a.outboundRecords
            else -> MutableStateFlow(emptyList<OutboundMessageRecord>()).asStateFlow()
        }

    val logs: StateFlow<List<String>>
        get() = when (val a = active()) {
            is NearbyMeshClient -> a.logs
            is WifiDirectMeshClient -> a.logs
            else -> MutableStateFlow(emptyList<String>()).asStateFlow()
        }

    fun start(identity: LocalIdentity, displayName: String?) {
        lastIdentity = identity
        lastDisplayName = displayName
        val pref = getPreferenceSync()
        val client = if (pref == "wifi_direct") wifiDirect else nearby
        if (current != client) {
            (current as? NearbyMeshClient)?.stop()
            (current as? WifiDirectMeshClient)?.stop()
            current = client
        }
        when (client) {
            is NearbyMeshClient -> client.start(identity, displayName)
            is WifiDirectMeshClient -> client.start(identity, displayName)
            else -> Unit
        }
    }

    fun stop() {
        lastIdentity = null
        lastDisplayName = null
        (current as? NearbyMeshClient)?.stop()
        (current as? WifiDirectMeshClient)?.stop()
    }

    fun buildChatEnvelope(
        sender: LocalIdentity,
        messageText: String,
        policy: AccessPolicy,
        ttl: Int = 3,
        recipientUserId: String? = null,
    ): MeshEnvelope? = when (val a = active()) {
        is NearbyMeshClient -> a.buildChatEnvelope(sender, messageText, policy, ttl, recipientUserId)
        is WifiDirectMeshClient -> a.buildChatEnvelope(sender, messageText, policy, ttl, recipientUserId)
        else -> null
    }

    fun sendChat(envelope: MeshEnvelope, previewText: String): Int = when (val a = active()) {
        is NearbyMeshClient -> a.sendChat(envelope, previewText)
        is WifiDirectMeshClient -> a.sendChat(envelope, previewText)
        else -> 0
    }

    fun sendToPeer(peerUserId: String, sender: LocalIdentity, content: OutboundContent): Int = when (val a = active()) {
        is NearbyMeshClient -> a.sendToPeer(peerUserId, sender, content)
        is WifiDirectMeshClient -> a.sendToPeer(peerUserId, sender, content)
        else -> 0
    }

    fun sendChatToPeer(peerUserId: String, sender: LocalIdentity, content: OutboundContent, policy: AccessPolicy, ttl: Int): Int = when (val a = active()) {
        is NearbyMeshClient -> a.sendChatToPeer(peerUserId, sender, content, policy, ttl)
        is WifiDirectMeshClient -> a.sendChatToPeer(peerUserId, sender, content, policy, ttl)
        else -> 0
    }

    fun broadcast(sender: LocalIdentity, content: OutboundContent) = when (val a = active()) {
        is NearbyMeshClient -> a.broadcast(sender, content)
        is WifiDirectMeshClient -> a.broadcast(sender, content)
        else -> Unit
    }

    fun rememberSentContent(content: OutboundContent) = when (val a = active()) {
        is NearbyMeshClient -> a.rememberSentContent(content)
        is WifiDirectMeshClient -> a.rememberSentContent(content)
        else -> Unit
    }

    fun requestHistoryFromPeer(peerId: String, sender: LocalIdentity): Int = when (val a = active()) {
        is NearbyMeshClient -> a.requestHistoryFromPeer(peerId, sender)
        is WifiDirectMeshClient -> a.requestHistoryFromPeer(peerId, sender)
        else -> 0
    }

    fun transportHealth(): TransportHealth = when (val a = active()) {
        is NearbyMeshClient -> a.transportHealth()
        is WifiDirectMeshClient -> a.transportHealth()
        else -> TransportHealth(com.peerdone.app.core.transport.TransportType.NEARBY, false, 999, 0, 10, 0)
    }

    fun setDropAcksForDemo(enabled: Boolean) = when (val a = active()) {
        is NearbyMeshClient -> a.setDropAcksForDemo(enabled)
        is WifiDirectMeshClient -> a.setDropAcksForDemo(enabled)
        else -> Unit
    }

    fun setForwardingEnabled(enabled: Boolean) = when (val a = active()) {
        is NearbyMeshClient -> a.setForwardingEnabled(enabled)
        is WifiDirectMeshClient -> a.setForwardingEnabled(enabled)
        else -> Unit
    }

    fun setPacketLossSimulation(inboundPercent: Int, outboundPercent: Int) = when (val a = active()) {
        is NearbyMeshClient -> a.setPacketLossSimulation(inboundPercent, outboundPercent)
        is WifiDirectMeshClient -> a.setPacketLossSimulation(inboundPercent, outboundPercent)
        else -> Unit
    }

    fun resetDeliveryMetrics() = when (val a = active()) {
        is NearbyMeshClient -> a.resetDeliveryMetrics()
        is WifiDirectMeshClient -> a.resetDeliveryMetrics()
        else -> Unit
    }
}
