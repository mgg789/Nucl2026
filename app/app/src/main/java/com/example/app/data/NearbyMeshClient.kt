package com.example.app.data

import android.content.Context
import com.example.app.core.message.ContentKind
import com.example.app.core.message.OutboundContent
import com.example.app.core.message.ContentCodec
import com.example.app.core.transport.TransportHealth
import com.example.app.core.transport.TransportType
import com.example.app.domain.AccessPolicy
import com.example.app.domain.LocalIdentity
import com.example.app.domain.MeshCrypto
import com.example.app.domain.MeshEnvelope
import com.example.app.domain.MeshMessageType
import com.example.app.domain.MeshSignature
import com.example.app.domain.PolicyKeyService
import com.example.app.domain.PolicyEngine
import com.example.app.domain.UserDirectoryEntry
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class ReceivedMeshMessage(
    val fromEndpointId: String,
    val envelope: MeshEnvelope,
    val decryptedText: String?,
    val accessGranted: Boolean,
    val contentKind: ContentKind?,
    val contentSummary: String,
)

data class MeshStats(
    val seenCount: Int = 0,
    val droppedDuplicates: Int = 0,
    val forwardedCount: Int = 0,
    val pendingAckCount: Int = 0,
    val ackReceivedCount: Int = 0,
)

data class TopologyEdge(
    val fromNode: String,
    val toNode: String,
)

data class TopologySnapshot(
    val nodes: List<String> = emptyList(),
    val edges: List<TopologyEdge> = emptyList(),
    val updatedAtMs: Long = System.currentTimeMillis(),
)

private data class PendingAck(
    val envelope: MeshEnvelope,
    var retries: Int = 0,
)

class NearbyMeshClient(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(appContext)
    private val strategy = Strategy.P2P_STAR
    private val serviceId = "${appContext.packageName}.mesh"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val signer = DeviceKeyStoreSigner()
    private val queueStore = MessageQueueStore(appContext)
    private val identityTrustStore = IdentityTrustStore(appContext)

    private var localIdentity: LocalIdentity? = null
    private val connected = linkedSetOf<String>()
    private val peerNamesByEndpoint = mutableMapOf<String, String>()
    private val seenMessageIds = linkedSetOf<String>()
    private val pendingAcks = ConcurrentHashMap<String, PendingAck>()
    private val knownNodes = linkedSetOf<String>()
    private val knownEdges = linkedSetOf<TopologyEdge>()
    private var droppedDuplicates = 0
    private var forwardedCount = 0
    private var ackReceivedCount = 0
    private var dropAcksForDemo = false
    private var topologySyncJob: Job? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _connectedEndpoints = MutableStateFlow<List<String>>(emptyList())
    val connectedEndpoints: StateFlow<List<String>> = _connectedEndpoints.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _incomingMessages = MutableStateFlow<List<ReceivedMeshMessage>>(emptyList())
    val incomingMessages: StateFlow<List<ReceivedMeshMessage>> = _incomingMessages.asStateFlow()

    private val _stats = MutableStateFlow(MeshStats())
    val stats: StateFlow<MeshStats> = _stats.asStateFlow()
    val outboundRecords: StateFlow<List<OutboundMessageRecord>> = queueStore.records

    private val _topology = MutableStateFlow(TopologySnapshot())
    val topology: StateFlow<TopologySnapshot> = _topology.asStateFlow()

    private fun log(line: String) {
        _logs.value = (_logs.value + line).takeLast(140)
    }

    private fun syncConnected() {
        _connectedEndpoints.value = connected.toList()
    }

    private fun syncStats() {
        _stats.value = MeshStats(
            seenCount = seenMessageIds.size,
            droppedDuplicates = droppedDuplicates,
            forwardedCount = forwardedCount,
            pendingAckCount = pendingAcks.size,
            ackReceivedCount = ackReceivedCount,
        )
    }

    private fun updateLocalTopology() {
        val me = localIdentity?.userId ?: return
        knownNodes += me
        connected.forEach { endpoint ->
            val peerName = peerNamesByEndpoint[endpoint] ?: endpoint
            knownNodes += peerName
            knownEdges += TopologyEdge(fromNode = me, toNode = peerName)
            knownEdges += TopologyEdge(fromNode = peerName, toNode = me)
        }
        publishTopology()
    }

    private fun mergeRemoteTopology(topologyJson: String) {
        runCatching {
            val json = JSONObject(topologyJson)
            val src = json.getString("sourceNode")
            val peers = json.optJSONArray("directPeers") ?: JSONArray()

            knownNodes += src
            for (i in 0 until peers.length()) {
                val peer = peers.getString(i)
                knownNodes += peer
                knownEdges += TopologyEdge(src, peer)
                knownEdges += TopologyEdge(peer, src)
            }
            publishTopology()
        }.onFailure {
            log("Topology parse failed: ${it.message}")
        }
    }

    private fun publishTopology() {
        _topology.value = TopologySnapshot(
            nodes = knownNodes.toList().sorted(),
            edges = knownEdges.toList().sortedWith(compareBy({ it.fromNode }, { it.toNode })),
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    private fun buildTopologyPayload(): String {
        val me = localIdentity?.userId ?: "unknown"
        val peers = connected.map { endpoint -> peerNamesByEndpoint[endpoint] ?: endpoint }
        return JSONObject()
            .put("sourceNode", me)
            .put("directPeers", JSONArray(peers))
            .put("timestampMs", System.currentTimeMillis())
            .toString()
    }

    private fun startTopologySync() {
        topologySyncJob?.cancel()
        topologySyncJob = scope.launch {
            while (_isRunning.value) {
                delay(4000)
                sendTopologyUpdate()
            }
        }
    }

    private fun sendTopologyUpdate() {
        val sender = localIdentity ?: return
        val targets = connected.toList()
        if (targets.isEmpty()) return
        val draft = MeshEnvelope.createTopology(
            sender = sender,
            senderPublicKeyBase64 = signer.publicKeyBase64(),
            topologyJson = buildTopologyPayload(),
            ttl = 2,
        )
        val signed = signEnvelope(draft)
        sendToTargets(targets, signed)
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            runCatching { MeshEnvelope.fromJsonBytes(bytes) }
                .onSuccess { envelope -> handleEnvelope(endpointId, envelope) }
                .onFailure { log("RX parse error from $endpointId") }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private fun handleEnvelope(fromEndpointId: String, envelope: MeshEnvelope) {
        if (!MeshSignature.verify(envelope)) {
            log("Dropped ${envelope.id.take(8)}: invalid signature")
            return
        }
        val trustOk = identityTrustStore.validateOrRemember(
            userId = envelope.senderUserId,
            orgId = envelope.senderOrgId,
            level = envelope.senderLevel,
            senderPublicKeyBase64 = envelope.senderPublicKeyBase64,
        )
        if (!trustOk) {
            log("Dropped ${envelope.id.take(8)}: trust mismatch for ${envelope.senderUserId}")
            return
        }

        when (envelope.type) {
            MeshMessageType.ACK -> {
                val ackFor = envelope.ackForId ?: return
                if (pendingAcks.remove(ackFor) != null) {
                    ackReceivedCount++
                    queueStore.markAcked(ackFor)
                    syncStats()
                    log("ACK for ${ackFor.take(8)} from ${envelope.senderUserId}")
                }
            }

            MeshMessageType.CHAT -> {
                if (!seenMessageIds.add(envelope.id)) {
                    droppedDuplicates++
                    syncStats()
                    return
                }
                syncStats()

                val identity = localIdentity
                val canRead = if (identity == null) {
                    false
                } else {
                    PolicyEngine.matches(
                        user = UserDirectoryEntry(
                            userId = identity.userId,
                            orgId = identity.orgId,
                            level = identity.level,
                        ),
                        policy = envelope.policy,
                    )
                }

                val plainText = if (canRead) {
                    val key = identity?.let { PolicyKeyService.resolveReadKey(it, envelope.keyId) }
                    if (key == null) {
                        null
                    } else {
                        runCatching {
                            MeshCrypto.decryptWithKey(envelope.ivBase64, envelope.cipherTextBase64, key)
                        }.getOrElse {
                            log("RX decrypt error ${envelope.id.take(8)}")
                            return
                        }
                    }
                } else {
                    null
                }

                _incomingMessages.value = (_incomingMessages.value + ReceivedMeshMessage(
                    fromEndpointId = fromEndpointId,
                    envelope = envelope,
                    decryptedText = plainText,
                    accessGranted = canRead && plainText != null,
                    contentKind = plainText?.let { ContentCodec.decode(it)?.kind },
                    contentSummary = plainText.toSummary(),
                ))
                    .takeLast(100)
                val grant = canRead && plainText != null
                log("RX ${envelope.id.take(8)} ${envelope.senderUserId} access=${if (grant) "granted" else "denied"}")

                if (!dropAcksForDemo) {
                    sendAck(fromEndpointId, envelope.id)
                } else {
                    log("ACK skipped (demo mode) for ${envelope.id.take(8)}")
                }

                if (envelope.ttl > 0) {
                    forward(envelope, fromEndpointId)
                }
            }

            MeshMessageType.TOPOLOGY -> {
                if (!seenMessageIds.add(envelope.id)) {
                    return
                }
                val topologyPayload = runCatching {
                    MeshCrypto.decryptControl(envelope.ivBase64, envelope.cipherTextBase64)
                }.getOrElse {
                    log("Topology decrypt error ${envelope.id.take(8)}")
                    return
                }
                mergeRemoteTopology(topologyPayload)
                if (envelope.ttl > 0) {
                    forward(envelope, fromEndpointId)
                }
            }
        }
    }

    private fun sendAck(targetEndpoint: String, msgId: String) {
        val sender = localIdentity ?: return
        val ackDraft = MeshEnvelope.createAck(
            sender = sender,
            senderPublicKeyBase64 = signer.publicKeyBase64(),
            ackForId = msgId,
        )
        val ack = signEnvelope(ackDraft)
        sendToTargets(listOf(targetEndpoint), ack)
    }

    private fun forward(original: MeshEnvelope, fromEndpointId: String) {
        val targets = connected.filter { it != fromEndpointId }
        if (targets.isEmpty()) return

        val forwarded = original.forForwarding(
            newTtl = (original.ttl - 1).coerceAtLeast(0),
            newHopCount = original.hopCount + 1,
        )
        sendToTargets(targets, forwarded)
        forwardedCount++
        syncStats()
        log("FWD ${forwarded.type.name} ${forwarded.id.take(8)} ttl=${forwarded.ttl} hop=${forwarded.hopCount}")
    }

    private fun sendToTargets(targets: List<String>, envelope: MeshEnvelope): Int {
        if (targets.isEmpty()) return 0
        val payload = Payload.fromBytes(envelope.toJsonBytes())
        connectionsClient.sendPayload(targets, payload)
            .addOnSuccessListener {
                log("TX ${envelope.type.name} ${envelope.id.take(8)} -> ${targets.size}")
            }
            .addOnFailureListener {
                log("TX failed ${envelope.id.take(8)}: ${it.message}")
            }
        return targets.size
    }

    private fun scheduleRetry(msgId: String) {
        scope.launch {
            while (true) {
                delay(2500)
                val pending = pendingAcks[msgId] ?: break
                if (pending.retries >= 2) {
                    pendingAcks.remove(msgId)
                    queueStore.markFailed(msgId)
                    syncStats()
                    log("Retry timeout ${msgId.take(8)}")
                    break
                }
                val targets = connected.toList()
                if (targets.isEmpty()) continue
                pending.retries++
                queueStore.incrementRetries(msgId)
                sendToTargets(targets, pending.envelope)
                syncStats()
                log("Retry #${pending.retries} for ${msgId.take(8)}")
            }
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            peerNamesByEndpoint[endpointId] = info.endpointName
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnSuccessListener { log("Accepted $endpointId (${info.endpointName})") }
                .addOnFailureListener { log("Accept failed $endpointId: ${it.message}") }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val status = result.status.statusCode
            if (status == ConnectionsStatusCodes.STATUS_OK) {
                connected += endpointId
                syncConnected()
                updateLocalTopology()
                sendTopologyUpdate()
                log("Connected: $endpointId")
            } else {
                log("Connect result $endpointId: $status")
            }
        }

        override fun onDisconnected(endpointId: String) {
            connected -= endpointId
            peerNamesByEndpoint.remove(endpointId)
            syncConnected()
            updateLocalTopology()
            log("Disconnected: $endpointId")
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            peerNamesByEndpoint[endpointId] = info.endpointName
            val localName = localIdentity?.userId ?: "unknown-node"
            log("Found endpoint: $endpointId (${info.endpointName})")
            connectionsClient.requestConnection(
                localName,
                endpointId,
                connectionLifecycleCallback,
            ).addOnFailureListener { log("Request conn failed $endpointId: ${it.message}") }
        }

        override fun onEndpointLost(endpointId: String) {
            peerNamesByEndpoint.remove(endpointId)
            log("Endpoint lost: $endpointId")
        }
    }

    fun start(identity: LocalIdentity) {
        if (_isRunning.value) return
        localIdentity = identity
        signer.useIdentity(identity.userId)
        knownNodes += identity.userId
        publishTopology()
        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(strategy)
            .build()
        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(strategy)
            .build()

        connectionsClient.startAdvertising(
            identity.userId,
            serviceId,
            connectionLifecycleCallback,
            advertisingOptions,
        ).addOnSuccessListener {
            log("Advertising started as ${identity.userId}")
        }.addOnFailureListener {
            log("Advertising failed: ${it.message}")
        }

        connectionsClient.startDiscovery(
            serviceId,
            discoveryCallback,
            discoveryOptions,
        ).addOnSuccessListener {
            log("Discovery started")
            _isRunning.value = true
            startTopologySync()
        }.addOnFailureListener {
            log("Discovery failed: ${it.message}")
            _isRunning.value = false
        }
    }

    fun stop() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        topologySyncJob?.cancel()
        connected.clear()
        peerNamesByEndpoint.clear()
        syncConnected()
        _isRunning.value = false
        pendingAcks.clear()
        syncStats()
        log("Nearby stopped")
    }

    fun sendChat(envelope: MeshEnvelope, previewText: String): Int {
        val targets = connected.toList()
        if (targets.isEmpty()) {
            log("No connected peers to send")
            return 0
        }
        queueStore.upsertQueued(envelope.id, previewText)
        val signed = signEnvelope(envelope)
        val sent = sendToTargets(targets, signed)
        queueStore.markSent(signed.id)
        seenMessageIds.add(signed.id)
        pendingAcks[signed.id] = PendingAck(envelope = signed)
        syncStats()
        scheduleRetry(signed.id)
        return sent
    }

    fun buildChatEnvelope(
        sender: LocalIdentity,
        messageText: String,
        policy: AccessPolicy,
        ttl: Int = 3,
    ): MeshEnvelope? {
        signer.useIdentity(sender.userId)
        val keySelection = PolicyKeyService.buildSendKey(sender, policy) ?: return null
        val (keyId, keyBytes) = keySelection
        val draft = MeshEnvelope.createChat(
            sender = sender,
            senderPublicKeyBase64 = signer.publicKeyBase64(),
            keyId = keyId,
            keyBytes = keyBytes,
            messageText = messageText,
            policy = policy,
            ttl = ttl,
        )
        return signEnvelope(draft)
    }

    private fun signEnvelope(draft: MeshEnvelope): MeshEnvelope {
        val signature = signer.sign(draft.signingString())
        return draft.copy(signatureBase64 = signature)
    }

    fun setDropAcksForDemo(enabled: Boolean) {
        dropAcksForDemo = enabled
        log("Demo ACK drop: ${if (enabled) "ON" else "OFF"}")
    }

    fun transportHealth(): TransportHealth {
        val peers = connected.size
        val available = _isRunning.value && peers > 0
        val latency = when {
            peers <= 0 -> 999
            peers == 1 -> 80
            peers <= 3 -> 120
            else -> 150
        }
        val bandwidth = when {
            peers <= 0 -> 0
            peers == 1 -> 8000
            peers <= 3 -> 6000
            else -> 4500
        }
        val battery = if (_isRunning.value) 6 else 1
        val stability = when {
            peers <= 0 -> 2
            peers == 1 -> 7
            peers <= 3 -> 8
            else -> 6
        }
        return TransportHealth(
            type = TransportType.NEARBY,
            available = available,
            estimatedLatencyMs = latency,
            estimatedBandwidthKbps = bandwidth,
            estimatedBatteryCost = battery,
            stabilityScore = stability,
        )
    }
}

private fun String?.toSummary(): String {
    if (this == null) return "hidden"
    val decoded = ContentCodec.decode(this) ?: return take(80)
    return when (decoded) {
        is OutboundContent.Text -> decoded.text.take(80)
        is OutboundContent.FileMeta -> "FILE ${decoded.fileName} (${decoded.totalBytes} bytes)"
        is OutboundContent.FileChunk -> "FILE_CHUNK ${decoded.fileId} ${decoded.chunkIndex + 1}/${decoded.chunkTotal}"
        is OutboundContent.VoiceNoteMeta -> "VOICE ${decoded.durationMs}ms ${decoded.codec}"
        is OutboundContent.VideoNoteMeta -> "VIDEO_NOTE ${decoded.durationMs}ms ${decoded.width}x${decoded.height}"
        is OutboundContent.CallSignal -> "CALL_SIGNAL ${decoded.phase} ${decoded.callId.take(8)}"
    }
}

