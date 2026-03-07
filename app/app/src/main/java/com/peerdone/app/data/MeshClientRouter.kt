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
 * Роутер выбора транспорта: при смене предпочтения (auto/nearby/wifi_direct/lan/all)
 * останавливает текущий клиент и запускает другой с теми же identity/displayName.
 * Режим "all" — одновременно слушает Nearby, WiFi Direct и LAN, объединяет пиров и сообщения.
 */
class MeshClientRouter(
    private val scope: CoroutineScope,
    private val preferenceFlow: kotlinx.coroutines.flow.Flow<String>,
    private val getPreferenceSync: () -> String,
    private val nearby: NearbyMeshClient,
    private val wifiDirect: WifiDirectMeshClient,
    private val lan: LanMeshClient,
    private val multi: MultiTransportMeshClient,
) {
    private var current: Any = nearby
    private var lastIdentity: LocalIdentity? = null
    private var lastDisplayName: String? = null

    private fun active(): Any = current

    private fun clientForPreference(pref: String): Any = when (pref) {
        "wifi_direct" -> wifiDirect
        "lan" -> lan
        "all" -> multi
        else -> nearby
    }

    init {
        scope.launch {
            preferenceFlow.collect { pref ->
                val next = clientForPreference(pref)
                if (next != current) {
                    (current as? NearbyMeshClient)?.stop()
                    (current as? WifiDirectMeshClient)?.stop()
                    (current as? LanMeshClient)?.stop()
                    (current as? MultiTransportMeshClient)?.stop()
                    current = next
                    lastIdentity?.let { id ->
                        lastDisplayName.let { name ->
                            when (next) {
                                is NearbyMeshClient -> next.start(id, name)
                                is WifiDirectMeshClient -> next.start(id, name)
                                is LanMeshClient -> next.start(id, name)
                                is MultiTransportMeshClient -> next.start(id, name)
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
            is LanMeshClient -> a.isRunning
            is MultiTransportMeshClient -> a.isRunning
            else -> MutableStateFlow(false).asStateFlow()
        }

    val connectedPeerInfos: StateFlow<List<NearbyMeshClient.PeerInfo>>
        get() = when (val a = active()) {
            is NearbyMeshClient -> a.connectedPeerInfos
            is WifiDirectMeshClient -> a.connectedPeerInfos
            is LanMeshClient -> a.connectedPeerInfos
            is MultiTransportMeshClient -> a.connectedPeerInfos
            else -> MutableStateFlow(emptyList<NearbyMeshClient.PeerInfo>()).asStateFlow()
        }

    val incomingMessages: StateFlow<List<ReceivedMeshMessage>>
        get() = when (val a = active()) {
            is NearbyMeshClient -> a.incomingMessages
            is WifiDirectMeshClient -> a.incomingMessages
            is LanMeshClient -> a.incomingMessages
            is MultiTransportMeshClient -> a.incomingMessages
            else -> MutableStateFlow(emptyList<ReceivedMeshMessage>()).asStateFlow()
        }

    val realtimeMessages: StateFlow<List<ReceivedMeshMessage>>
        get() = when (val a = active()) {
            is NearbyMeshClient -> a.realtimeMessages
            is WifiDirectMeshClient -> a.realtimeMessages
            is LanMeshClient -> a.realtimeMessages
            is MultiTransportMeshClient -> a.realtimeMessages
            else -> MutableStateFlow(emptyList<ReceivedMeshMessage>()).asStateFlow()
        }

    val topology: StateFlow<TopologySnapshot>
        get() = when (val a = active()) {
            is NearbyMeshClient -> a.topology
            is WifiDirectMeshClient -> a.topology
            is LanMeshClient -> a.topology
            is MultiTransportMeshClient -> a.topology
            else -> MutableStateFlow(TopologySnapshot()).asStateFlow()
        }

    val deliveryMetrics: StateFlow<DeliveryMetrics>
        get() = when (val a = active()) {
            is NearbyMeshClient -> a.deliveryMetrics
            is WifiDirectMeshClient -> a.deliveryMetrics
            is LanMeshClient -> a.deliveryMetrics
            is MultiTransportMeshClient -> a.deliveryMetrics
            else -> MutableStateFlow(DeliveryMetrics()).asStateFlow()
        }

    val chatHistoryStore: ChatHistoryStoreInterface
        get() = when (val a = active()) {
            is NearbyMeshClient -> a.chatHistoryStore
            is WifiDirectMeshClient -> a.chatHistoryStore
            is LanMeshClient -> a.chatHistoryStore
            is MultiTransportMeshClient -> a.chatHistoryStore
            else -> throw IllegalStateException("No active client")
        }

    val connectedEndpoints: StateFlow<List<String>>
        get() = when (val a = active()) {
            is NearbyMeshClient -> a.connectedEndpoints
            is WifiDirectMeshClient -> a.connectedEndpoints
            is LanMeshClient -> a.connectedEndpoints
            is MultiTransportMeshClient -> a.connectedEndpoints
            else -> MutableStateFlow(emptyList<String>()).asStateFlow()
        }

    val stats: StateFlow<MeshStats>
        get() = when (val a = active()) {
            is NearbyMeshClient -> a.stats
            is WifiDirectMeshClient -> a.stats
            is LanMeshClient -> a.stats
            is MultiTransportMeshClient -> a.stats
            else -> MutableStateFlow(MeshStats()).asStateFlow()
        }

    val testConfig: StateFlow<NetworkTestConfig>
        get() = when (val a = active()) {
            is NearbyMeshClient -> a.testConfig
            is WifiDirectMeshClient -> a.testConfig
            is LanMeshClient -> a.testConfig
            is MultiTransportMeshClient -> a.testConfig
            else -> MutableStateFlow(NetworkTestConfig()).asStateFlow()
        }

    val outboundRecords: StateFlow<List<OutboundMessageRecord>>
        get() = when (val a = active()) {
            is NearbyMeshClient -> a.outboundRecords
            is WifiDirectMeshClient -> a.outboundRecords
            is LanMeshClient -> a.outboundRecords
            is MultiTransportMeshClient -> a.outboundRecords
            else -> MutableStateFlow(emptyList<OutboundMessageRecord>()).asStateFlow()
        }

    val logs: StateFlow<List<String>>
        get() = when (val a = active()) {
            is NearbyMeshClient -> a.logs
            is WifiDirectMeshClient -> a.logs
            is LanMeshClient -> a.logs
            is MultiTransportMeshClient -> a.logs
            else -> MutableStateFlow(emptyList<String>()).asStateFlow()
        }

    fun start(identity: LocalIdentity, displayName: String?) {
        lastIdentity = identity
        lastDisplayName = displayName
        val pref = getPreferenceSync()
        val client = clientForPreference(pref)
        if (current != client) {
            (current as? NearbyMeshClient)?.stop()
            (current as? WifiDirectMeshClient)?.stop()
            (current as? LanMeshClient)?.stop()
            (current as? MultiTransportMeshClient)?.stop()
            current = client
        }
        when (client) {
            is NearbyMeshClient -> client.start(identity, displayName)
            is WifiDirectMeshClient -> client.start(identity, displayName)
            is LanMeshClient -> client.start(identity, displayName)
            is MultiTransportMeshClient -> client.start(identity, displayName)
            else -> Unit
        }
    }

    fun stop() {
        lastIdentity = null
        lastDisplayName = null
        (current as? NearbyMeshClient)?.stop()
        (current as? WifiDirectMeshClient)?.stop()
        (current as? LanMeshClient)?.stop()
        (current as? MultiTransportMeshClient)?.stop()
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
        is LanMeshClient -> a.buildChatEnvelope(sender, messageText, policy, ttl, recipientUserId)
        is MultiTransportMeshClient -> a.buildChatEnvelope(sender, messageText, policy, ttl, recipientUserId)
        else -> null
    }

    fun sendChat(envelope: MeshEnvelope, previewText: String): Int = when (val a = active()) {
        is NearbyMeshClient -> a.sendChat(envelope, previewText)
        is WifiDirectMeshClient -> a.sendChat(envelope, previewText)
        is LanMeshClient -> a.sendChat(envelope, previewText)
        is MultiTransportMeshClient -> a.sendChat(envelope, previewText)
        else -> 0
    }

    fun sendToPeer(peerUserId: String, sender: LocalIdentity, content: OutboundContent): Int = when (val a = active()) {
        is NearbyMeshClient -> a.sendToPeer(peerUserId, sender, content)
        is WifiDirectMeshClient -> a.sendToPeer(peerUserId, sender, content)
        is LanMeshClient -> a.sendToPeer(peerUserId, sender, content)
        is MultiTransportMeshClient -> a.sendToPeer(peerUserId, sender, content)
        else -> 0
    }

    fun sendChatToPeer(peerUserId: String, sender: LocalIdentity, content: OutboundContent, policy: AccessPolicy, ttl: Int): Int = when (val a = active()) {
        is NearbyMeshClient -> a.sendChatToPeer(peerUserId, sender, content, policy, ttl)
        is WifiDirectMeshClient -> a.sendChatToPeer(peerUserId, sender, content, policy, ttl)
        is LanMeshClient -> a.sendChatToPeer(peerUserId, sender, content, policy, ttl)
        is MultiTransportMeshClient -> a.sendChatToPeer(peerUserId, sender, content, policy, ttl)
        else -> 0
    }

    fun broadcast(sender: LocalIdentity, content: OutboundContent) = when (val a = active()) {
        is NearbyMeshClient -> a.broadcast(sender, content)
        is WifiDirectMeshClient -> a.broadcast(sender, content)
        is LanMeshClient -> a.broadcast(sender, content)
        is MultiTransportMeshClient -> a.broadcast(sender, content)
        else -> Unit
    }

    fun rememberSentContent(content: OutboundContent) = when (val a = active()) {
        is NearbyMeshClient -> a.rememberSentContent(content)
        is WifiDirectMeshClient -> a.rememberSentContent(content)
        is LanMeshClient -> a.rememberSentContent(content)
        is MultiTransportMeshClient -> a.rememberSentContent(content)
        else -> Unit
    }

    fun requestHistoryFromPeer(peerId: String, sender: LocalIdentity): Int = when (val a = active()) {
        is NearbyMeshClient -> a.requestHistoryFromPeer(peerId, sender)
        is WifiDirectMeshClient -> a.requestHistoryFromPeer(peerId, sender)
        is LanMeshClient -> a.requestHistoryFromPeer(peerId, sender)
        is MultiTransportMeshClient -> a.requestHistoryFromPeer(peerId, sender)
        else -> 0
    }

    fun transportHealth(): TransportHealth = when (val a = active()) {
        is NearbyMeshClient -> a.transportHealth()
        is WifiDirectMeshClient -> a.transportHealth()
        is LanMeshClient -> a.transportHealth()
        is MultiTransportMeshClient -> a.transportHealth()
        else -> TransportHealth(com.peerdone.app.core.transport.TransportType.NEARBY, false, 999, 0, 10, 0)
    }

    fun setDropAcksForDemo(enabled: Boolean) = when (val a = active()) {
        is NearbyMeshClient -> a.setDropAcksForDemo(enabled)
        is WifiDirectMeshClient -> a.setDropAcksForDemo(enabled)
        is LanMeshClient -> a.setDropAcksForDemo(enabled)
        is MultiTransportMeshClient -> a.setDropAcksForDemo(enabled)
        else -> Unit
    }

    fun setForwardingEnabled(enabled: Boolean) = when (val a = active()) {
        is NearbyMeshClient -> a.setForwardingEnabled(enabled)
        is WifiDirectMeshClient -> a.setForwardingEnabled(enabled)
        is LanMeshClient -> a.setForwardingEnabled(enabled)
        is MultiTransportMeshClient -> a.setForwardingEnabled(enabled)
        else -> Unit
    }

    fun setPacketLossSimulation(inboundPercent: Int, outboundPercent: Int) = when (val a = active()) {
        is NearbyMeshClient -> a.setPacketLossSimulation(inboundPercent, outboundPercent)
        is WifiDirectMeshClient -> a.setPacketLossSimulation(inboundPercent, outboundPercent)
        is LanMeshClient -> a.setPacketLossSimulation(inboundPercent, outboundPercent)
        is MultiTransportMeshClient -> a.setPacketLossSimulation(inboundPercent, outboundPercent)
        else -> Unit
    }

    fun resetDeliveryMetrics() = when (val a = active()) {
        is NearbyMeshClient -> a.resetDeliveryMetrics()
        is WifiDirectMeshClient -> a.resetDeliveryMetrics()
        is LanMeshClient -> a.resetDeliveryMetrics()
        is MultiTransportMeshClient -> a.resetDeliveryMetrics()
        else -> Unit
    }
}
