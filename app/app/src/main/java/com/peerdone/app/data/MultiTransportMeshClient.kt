package com.peerdone.app.data

import com.peerdone.app.core.message.OutboundContent
import com.peerdone.app.core.transport.TransportHealth
import com.peerdone.app.core.transport.TransportType
import com.peerdone.app.domain.AccessPolicy
import com.peerdone.app.domain.LocalIdentity
import com.peerdone.app.domain.MeshEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Клиент «все протоколы»: одновременно запускает Nearby, WiFi Direct и LAN,
 * объединяет списки пиров и входящие сообщения, при отправке шлёт по всем транспортам, где есть получатель.
 */
class MultiTransportMeshClient(
    private val scope: CoroutineScope,
    private val nearby: NearbyMeshClient,
    private val wifiDirect: WifiDirectMeshClient,
    private val lan: LanMeshClient,
) {
    private val mergedChatStore = MergedChatHistoryStore(
        scope,
        listOf(nearby.chatHistoryStore, wifiDirect.chatHistoryStore, lan.chatHistoryStore)
    )

    private val _connectedPeerInfos = MutableStateFlow<List<NearbyMeshClient.PeerInfo>>(emptyList())
    val connectedPeerInfos: StateFlow<List<NearbyMeshClient.PeerInfo>> = _connectedPeerInfos.asStateFlow()

    private val _incomingMessages = MutableStateFlow<List<ReceivedMeshMessage>>(emptyList())
    val incomingMessages: StateFlow<List<ReceivedMeshMessage>> = _incomingMessages.asStateFlow()

    private val _realtimeMessages = MutableStateFlow<List<ReceivedMeshMessage>>(emptyList())
    val realtimeMessages: StateFlow<List<ReceivedMeshMessage>> = _realtimeMessages.asStateFlow()

    private val _connectedEndpoints = MutableStateFlow<List<String>>(emptyList())
    val connectedEndpoints: StateFlow<List<String>> = _connectedEndpoints.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _topology = MutableStateFlow(TopologySnapshot())
    val topology: StateFlow<TopologySnapshot> = _topology.asStateFlow()

    private val _deliveryMetrics = MutableStateFlow(DeliveryMetrics())
    val deliveryMetrics: StateFlow<DeliveryMetrics> = _deliveryMetrics.asStateFlow()

    private val _stats = MutableStateFlow(MeshStats())
    val stats: StateFlow<MeshStats> = _stats.asStateFlow()

    private val _testConfig = MutableStateFlow(NetworkTestConfig())
    val testConfig: StateFlow<NetworkTestConfig> = _testConfig.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    val chatHistoryStore: ChatHistoryStoreInterface get() = mergedChatStore
    val outboundRecords: StateFlow<List<OutboundMessageRecord>> = nearby.outboundRecords

    private var mergeParent: Job? = null

    fun start(identity: LocalIdentity, displayName: String?) {
        nearby.start(identity, displayName)
        wifiDirect.start(identity, displayName)
        lan.start(identity, displayName)

        val mergedPeerListProvider: () -> List<String> = { _connectedPeerInfos.value.map { it.userId } }
        nearby.topologyPeerListProvider = mergedPeerListProvider
        lan.topologyPeerListProvider = mergedPeerListProvider

        mergeParent?.cancel()
        val parent = Job()
        mergeParent = parent
        val mergeScope = CoroutineScope(scope.coroutineContext + parent)

        mergeScope.launch {
            combine(
                nearby.connectedPeerInfos,
                wifiDirect.connectedPeerInfos,
                lan.connectedPeerInfos,
            ) { a, b, c -> (a + b + c).distinctBy { it.userId } }
                .collect { _connectedPeerInfos.value = it }
        }
        mergeScope.launch {
            combine(
                nearby.connectedEndpoints,
                wifiDirect.connectedEndpoints,
                lan.connectedEndpoints,
            ) { a, b, c -> (a + b + c).distinct() }
                .collect { _connectedEndpoints.value = it }
        }
        mergeScope.launch {
            combine(
                nearby.incomingMessages,
                wifiDirect.incomingMessages,
                lan.incomingMessages,
            ) { a, b, c ->
                (a + b + c).distinctBy { it.envelope.id }.sortedBy { it.envelope.timestampMs }
            }.collect { _incomingMessages.value = it }
        }
        mergeScope.launch {
            combine(
                nearby.realtimeMessages,
                wifiDirect.realtimeMessages,
                lan.realtimeMessages,
            ) { a, b, c ->
                (a + b + c).distinctBy { it.envelope.id }
            }.collect { _realtimeMessages.value = it }
        }
        mergeScope.launch {
            combine(
                nearby.isRunning,
                wifiDirect.isRunning,
                lan.isRunning,
            ) { na, wf, ln -> na || wf || ln }
                .collect { _isRunning.value = it }
        }
        mergeScope.launch {
            combine(
                nearby.topology,
                wifiDirect.topology,
                lan.topology,
            ) { t1, t2, t3 ->
                val nodes = (t1.nodes + t2.nodes + t3.nodes).distinct()
                val edges = (t1.edges + t2.edges + t3.edges).distinctBy { "${it.fromNode}-${it.toNode}" }
                TopologySnapshot(nodes = nodes, edges = edges, updatedAtMs = maxOf(t1.updatedAtMs, t2.updatedAtMs, t3.updatedAtMs))
            }.collect { _topology.value = it }
        }
        mergeScope.launch {
            nearby.deliveryMetrics.collect { _deliveryMetrics.value = it }
        }
        mergeScope.launch {
            nearby.stats.collect { _stats.value = it }
        }
        mergeScope.launch {
            nearby.testConfig.collect { _testConfig.value = it }
        }
        mergeScope.launch {
            combine(
                nearby.logs,
                wifiDirect.logs,
                lan.logs,
            ) { a, b, c -> (a + b + c).takeLast(200) }
                .collect { _logs.value = it }
        }
    }

    fun stop() {
        mergeParent?.cancel()
        mergeParent = null
        nearby.topologyPeerListProvider = null
        lan.topologyPeerListProvider = null
        nearby.stop()
        wifiDirect.stop()
        lan.stop()
        _connectedPeerInfos.value = emptyList()
        _incomingMessages.value = emptyList()
        _realtimeMessages.value = emptyList()
        _connectedEndpoints.value = emptyList()
        _isRunning.value = false
    }

    fun buildChatEnvelope(
        sender: LocalIdentity,
        messageText: String,
        policy: AccessPolicy,
        ttl: Int = 3,
        recipientUserId: String? = null,
    ): MeshEnvelope? = nearby.buildChatEnvelope(sender, messageText, policy, ttl, recipientUserId)

    fun sendChat(envelope: MeshEnvelope, previewText: String): Int {
        val n = nearby.sendChat(envelope, previewText)
        val w = wifiDirect.sendChat(envelope, previewText)
        val l = lan.sendChat(envelope, previewText)
        return maxOf(n, w, l)
    }

    fun sendToPeer(peerUserId: String, sender: LocalIdentity, content: OutboundContent): Int {
        val n = nearby.sendToPeer(peerUserId, sender, content)
        val w = wifiDirect.sendToPeer(peerUserId, sender, content)
        val l = lan.sendToPeer(peerUserId, sender, content)
        return maxOf(n, w, l)
    }

    fun sendChatToPeer(peerUserId: String, sender: LocalIdentity, content: OutboundContent, policy: AccessPolicy, ttl: Int): Int {
        val n = nearby.sendChatToPeer(peerUserId, sender, content, policy, ttl)
        val w = wifiDirect.sendChatToPeer(peerUserId, sender, content, policy, ttl)
        val l = lan.sendChatToPeer(peerUserId, sender, content, policy, ttl)
        return maxOf(n, w, l)
    }

    fun broadcast(sender: LocalIdentity, content: OutboundContent) {
        nearby.broadcast(sender, content)
        wifiDirect.broadcast(sender, content)
        lan.broadcast(sender, content)
    }

    fun rememberSentContent(content: OutboundContent) {
        nearby.rememberSentContent(content)
        wifiDirect.rememberSentContent(content)
        lan.rememberSentContent(content)
    }

    fun requestHistoryFromPeer(peerId: String, sender: LocalIdentity): Int {
        val n = nearby.requestHistoryFromPeer(peerId, sender)
        val w = wifiDirect.requestHistoryFromPeer(peerId, sender)
        val l = lan.requestHistoryFromPeer(peerId, sender)
        return maxOf(n, w, l)
    }

    fun transportHealth(): TransportHealth =
        TransportHealth(TransportType.NEARBY, isRunning.value, 0, 0, 10, 0)

    fun setDropAcksForDemo(enabled: Boolean) {
        nearby.setDropAcksForDemo(enabled)
        wifiDirect.setDropAcksForDemo(enabled)
        lan.setDropAcksForDemo(enabled)
    }

    fun setForwardingEnabled(enabled: Boolean) {
        nearby.setForwardingEnabled(enabled)
        wifiDirect.setForwardingEnabled(enabled)
        lan.setForwardingEnabled(enabled)
    }

    fun setPacketLossSimulation(inboundPercent: Int, outboundPercent: Int) {
        nearby.setPacketLossSimulation(inboundPercent, outboundPercent)
        wifiDirect.setPacketLossSimulation(inboundPercent, outboundPercent)
        lan.setPacketLossSimulation(inboundPercent, outboundPercent)
    }

    fun resetDeliveryMetrics() {
        nearby.resetDeliveryMetrics()
        wifiDirect.resetDeliveryMetrics()
        lan.resetDeliveryMetrics()
    }
}
