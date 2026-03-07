package com.peerdone.app.data

import android.content.Context
import android.os.Build
import com.peerdone.app.core.message.ContentCodec
import com.peerdone.app.core.message.ContentKind
import com.peerdone.app.core.message.HistoryResponseItem
import com.peerdone.app.core.message.OutboundContent
import com.peerdone.app.core.transport.TransportHealth
import com.peerdone.app.core.transport.TransportType
import com.peerdone.app.domain.AccessPolicy
import com.peerdone.app.domain.LocalIdentity
import com.peerdone.app.domain.MeshCrypto
import com.peerdone.app.domain.MeshEnvelope
import com.peerdone.app.domain.MeshMessageType
import com.peerdone.app.domain.MeshSignature
import com.peerdone.app.domain.PolicyKeyService
import com.peerdone.app.domain.PolicyEngine
import com.peerdone.app.domain.UserDirectoryEntry
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
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import android.util.Log
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

data class ReceivedMeshMessage(
    val fromEndpointId: String,
    val envelope: MeshEnvelope,
    val decryptedText: String?,
    val accessGranted: Boolean,
    val contentKind: ContentKind?,
    val contentSummary: String,
    val receivedFilePath: String? = null,
    val receivedFileName: String? = null,
    val receivedFileMimeType: String? = null,
)

data class MeshStats(
    val seenCount: Int = 0,
    val droppedDuplicates: Int = 0,
    val forwardedCount: Int = 0,
    val pendingAckCount: Int = 0,
    val ackReceivedCount: Int = 0,
)

data class DeliveryMetrics(
    val sentCount: Int = 0,
    val ackedCount: Int = 0,
    val lostCount: Int = 0,
    val avgAckRttMs: Long = 0,
    val p95AckRttMs: Long = 0,
    val lossPercent: Int = 0,
)

data class NetworkTestConfig(
    val forwardingEnabled: Boolean = true,
    val inboundDropPercent: Int = 0,
    val outboundDropPercent: Int = 0,
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
    private val strategy = Strategy.P2P_CLUSTER
    private val serviceId = "${appContext.packageName}.mesh"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val signer = DeviceKeyStoreSigner()
    private val queueStore = MessageQueueStore(appContext)
    private val identityTrustStore = IdentityTrustStore(appContext)
    private val incomingFileStore = IncomingFileStore(appContext)
    val chatHistoryStore = ChatHistoryStore(appContext)

    private var localIdentity: LocalIdentity? = null
    private var currentEndpointName: String? = null
    private val connected = Collections.synchronizedSet(linkedSetOf<String>())
    private val peerNamesByEndpoint = ConcurrentHashMap<String, String>()
    private val seenMessageIds = Collections.synchronizedSet(linkedSetOf<String>())
    private val pendingAcks = ConcurrentHashMap<String, PendingAck>()
    private val knownNodes = Collections.synchronizedSet(linkedSetOf<String>())
    private val knownEdges = Collections.synchronizedSet(linkedSetOf<TopologyEdge>())
    
    private var advertisingStarted = false
    private var discoveryStarted = false
    private val maxSeenMessageIds = 5000
    private var droppedDuplicates = 0
    private var forwardedCount = 0
    private var ackReceivedCount = 0
    private var sentCount = 0
    private var lostCount = 0
    private val ackRttsMs = mutableListOf<Long>()
    private val pendingSentAtMs = ConcurrentHashMap<String, Long>()
    private var dropAcksForDemo = false
    private var forwardingEnabled = true
    private var inboundDropPercent = 0
    private var outboundDropPercent = 0
    private var topologySyncJob: Job? = null
    private val incomingFiles = ConcurrentHashMap<String, IncomingFileState>()
    private val sentFileChunks = ConcurrentHashMap<String, MutableMap<Int, OutboundContent.FileChunk>>()
    private val sentFileMeta = ConcurrentHashMap<String, OutboundContent.FileMeta>()
    private val repairRequestSentAtMs = ConcurrentHashMap<String, Long>()
    private val inboundRateWindowMs = 5_000L
    /** Лимит входящих сообщений от одного отправителя за окно (остальные отбрасываются). */
    private val inboundRateLimit = 15
    private val inboundSenderEvents = ConcurrentHashMap<String, MutableList<Long>>()
    private data class CompletedFileInfo(val fileName: String, val path: String, val mimeType: String?)
    private val completedReceivedFiles = ConcurrentHashMap<String, CompletedFileInfo>()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _connectedEndpoints = MutableStateFlow<List<String>>(emptyList())
    val connectedEndpoints: StateFlow<List<String>> = _connectedEndpoints.asStateFlow()

    data class PeerInfo(val endpointId: String, val userId: String, val deviceModel: String, val displayName: String = "")
    private val _connectedPeerInfos = MutableStateFlow<List<PeerInfo>>(emptyList())
    val connectedPeerInfos: StateFlow<List<PeerInfo>> = _connectedPeerInfos.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _incomingMessages = MutableStateFlow<List<ReceivedMeshMessage>>(emptyList())
    val incomingMessages: StateFlow<List<ReceivedMeshMessage>> = _incomingMessages.asStateFlow()

    /** Realtime messages (call signals, audio packets) — not rate-limited, not shown in chat. */
    private val _realtimeMessages = MutableStateFlow<List<ReceivedMeshMessage>>(emptyList())
    val realtimeMessages: StateFlow<List<ReceivedMeshMessage>> = _realtimeMessages.asStateFlow()

    private val _stats = MutableStateFlow(MeshStats())
    val stats: StateFlow<MeshStats> = _stats.asStateFlow()
    val outboundRecords: StateFlow<List<OutboundMessageRecord>> = queueStore.records

    private val _topology = MutableStateFlow(TopologySnapshot())
    val topology: StateFlow<TopologySnapshot> = _topology.asStateFlow()
    private val _deliveryMetrics = MutableStateFlow(DeliveryMetrics())
    val deliveryMetrics: StateFlow<DeliveryMetrics> = _deliveryMetrics.asStateFlow()
    private val _testConfig = MutableStateFlow(NetworkTestConfig())
    val testConfig: StateFlow<NetworkTestConfig> = _testConfig.asStateFlow()

    private data class IncomingFileState(
        val meta: OutboundContent.FileMeta? = null,
        val chunks: Map<Int, ByteArray> = emptyMap(),
        val chunkTotal: Int? = null,
    )

    init {
        incomingFileStore.loadAll().forEach { persisted ->
            incomingFiles[persisted.fileId] = IncomingFileState(
                meta = persisted.meta,
                chunks = persisted.chunks,
                chunkTotal = persisted.chunkTotal,
            )
        }
        syncDeliveryMetrics()
    }

    private fun log(line: String) {
        _logs.value = (_logs.value + line).takeLast(140)
    }

    private fun syncConnected() {
        _connectedEndpoints.value = connected.toList()
        syncConnectedPeerInfos()
    }

    private fun syncConnectedPeerInfos() {
        val list = connected.toList().map { endpointId ->
            val raw = peerNamesByEndpoint[endpointId] ?: endpointId
            val parts = raw.split("|")
            val uid = parts.getOrNull(0)?.trim() ?: raw
            val model = parts.getOrNull(1)?.take(50) ?: ""
            val displayName = parts.getOrNull(2)?.take(30)?.trim() ?: ""
            PeerInfo(endpointId, uid, model, displayName)
        }
        _connectedPeerInfos.value = list
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
    
    private fun trimSeenMessageIds() {
        if (seenMessageIds.size > maxSeenMessageIds) {
            synchronized(seenMessageIds) {
                val toRemove = seenMessageIds.take(1000)
                seenMessageIds.removeAll(toRemove.toSet())
            }
        }
    }

    private fun syncDeliveryMetrics() {
        val sorted = ackRttsMs.sorted()
        val p95 = if (sorted.isEmpty()) 0L else sorted[((sorted.size - 1) * 95) / 100]
        val avg = if (sorted.isEmpty()) 0L else sorted.average().toLong()
        val lossPct = if (sentCount <= 0) 0 else ((lostCount * 100.0) / sentCount).toInt()
        _deliveryMetrics.value = DeliveryMetrics(
            sentCount = sentCount,
            ackedCount = ackReceivedCount,
            lostCount = lostCount,
            avgAckRttMs = avg,
            p95AckRttMs = p95,
            lossPercent = lossPct,
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
                .onSuccess { envelope ->
                    if (shouldDropInbound(envelope)) {
                        log("DROP inbound ${envelope.type.name} ${envelope.id.take(8)} test=${inboundDropPercent}%")
                        return@onSuccess
                    }
                    handleEnvelope(endpointId, envelope)
                }
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
                    pendingSentAtMs.remove(ackFor)?.let { startedAt ->
                        val rtt = (System.currentTimeMillis() - startedAt).coerceAtLeast(0)
                        ackRttsMs += rtt
                        if (ackRttsMs.size > 300) {
                            ackRttsMs.removeAt(0)
                        }
                    }
                    queueStore.markAcked(ackFor)
                    syncStats()
                    syncDeliveryMetrics()
                    log("ACK for ${ackFor.take(8)} from ${envelope.senderUserId}")
                }
            }

            MeshMessageType.CHAT -> {
                if (!seenMessageIds.add(envelope.id)) {
                    droppedDuplicates++
                    syncStats()
                    return
                }
                trimSeenMessageIds()
                syncStats()

                val recipient = envelope.recipientUserId?.substringBefore("|")?.trim()
                val identity = localIdentity
                val iAmRecipient = recipient != null && identity != null && identity.userId.substringBefore("|").trim() == recipient
                if (recipient != null && !iAmRecipient) {
                    if (envelope.ttl > 0) forward(envelope, fromEndpointId)
                    return
                }

                val canRead = if (identity == null) {
                    false
                } else if (recipient != null) {
                    iAmRecipient
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

                val content = plainText?.let { ContentCodec.decode(it) }

                // Realtime (calls/audio): только 1:1, не форвардить — иначе звонок приходит всем в сети
                if (content is OutboundContent.CallSignal || content is OutboundContent.AudioPacket) {
                    if (content is OutboundContent.CallSignal) {
                        Log.d("PeerDoneCall", "RX CallSignal phase=${content.phase} callId=${content.callId.take(8)} recipient=${envelope.recipientUserId?.take(20)} canRead=$canRead plainTextNull=${plainText == null}")
                    }
                    val msg = ReceivedMeshMessage(
                        fromEndpointId = fromEndpointId,
                        envelope = envelope,
                        decryptedText = plainText,
                        accessGranted = canRead && plainText != null,
                        contentKind = content.kind,
                        contentSummary = "",
                    )
                    _realtimeMessages.value = (_realtimeMessages.value + msg).takeLast(500)
                    if (!dropAcksForDemo) sendAck(fromEndpointId, envelope.id)
                    // Не вызываем forward() — звонки и аудио только для адресата
                    return
                }

                // Запрос/ответ истории чата — не показывать в ленте, только обработать
                if (content is OutboundContent.HistoryRequest && identity != null) {
                    val senderPeerId = envelope.senderUserId.substringBefore("|").trim()
                    val ourMessages = chatHistoryStore.getMessagesForPeerSync(senderPeerId)
                    val items = ourMessages.map { m: StoredChatMessage ->
                        HistoryResponseItem(m.id, m.text, m.timestampMs, m.isOutgoing, m.type.name, m.fileName, m.filePath, m.voiceFileId)
                    }
                    val response = OutboundContent.HistoryResponse(content.requestId, items)
                    sendChatToPeer(senderPeerId, identity, response, AccessPolicy(), ttl = 1)
                    if (!dropAcksForDemo) sendAck(fromEndpointId, envelope.id)
                    return
                }
                if (content is OutboundContent.HistoryResponse) {
                    val senderPeerId = envelope.senderUserId.substringBefore("|").trim()
                    // У отправителя ответа isOutgoing = его исходящие; для нас его исходящие = наши входящие
                    val stored = content.messages.map { m ->
                        StoredChatMessage(
                            id = m.id,
                            text = m.text,
                            timestampMs = m.timestampMs,
                            isOutgoing = !m.isOutgoing,
                            type = runCatching { StoredMessageType.valueOf(m.type) }.getOrElse { StoredMessageType.TEXT },
                            fileName = m.fileName,
                            filePath = m.filePath,
                            voiceFileId = m.voiceFileId,
                        )
                    }
                    chatHistoryStore.addMessages(senderPeerId, stored)
                    if (!dropAcksForDemo) sendAck(fromEndpointId, envelope.id)
                    return
                }

                // File transfer: do not rate-limit (иначе большие файлы не доходят)
                val skipRateLimit = content is OutboundContent.FileMeta || content is OutboundContent.FileChunk
                val rateLimitKey = envelope.senderUserId.substringBefore("|").trim()
                if (!skipRateLimit && isSenderRateLimited(rateLimitKey)) {
                    log("Dropped ${envelope.id.take(8)}: rate limit for $rateLimitKey")
                    return
                }

                if (content is OutboundContent.FileRepairRequest) {
                    handleFileRepairRequest(content, fromEndpointId)
                    return
                }
                val enrichedSummary = buildIncomingSummary(content, envelope.senderUserId)
                val completedFile = (content as? OutboundContent.FileChunk)?.let { chunk ->
                    completedReceivedFiles[chunk.fileId]
                }
                val receivedFilePath = completedFile?.path
                val receivedFileName = completedFile?.fileName
                val receivedFileMimeType = completedFile?.mimeType

                // Do not add FileMeta, FileRepairRequest or incomplete FileChunk to chat
                val shouldAddToChat = content !is OutboundContent.FileMeta &&
                    content !is OutboundContent.FileRepairRequest &&
                    (content !is OutboundContent.FileChunk || receivedFilePath != null)

                // Deduplicate: in mesh the same file can "complete" from multiple paths (different envelope.id)
                fun norm(s: String) = s.substringBefore("|").trim()
                val isFileOrVoiceCompletion = receivedFilePath != null
                val alreadyHaveSameFile = isFileOrVoiceCompletion && _incomingMessages.value.any {
                    norm(it.envelope.senderUserId) == norm(envelope.senderUserId) && it.receivedFilePath == receivedFilePath
                }

                if (shouldAddToChat && !alreadyHaveSameFile) {
                    val displaySummary = when {
                        receivedFilePath != null && receivedFileMimeType?.startsWith("audio/") == true -> "Голосовое сообщение"
                        receivedFilePath != null && receivedFileMimeType?.startsWith("video/") == true -> "Видео"
                        receivedFileName != null -> receivedFileName
                        else -> enrichedSummary ?: plainText.toSummary()
                    }
                    val isAudio = receivedFilePath != null && receivedFileMimeType?.startsWith("audio/") == true
                    val isVideo = receivedFilePath != null && receivedFileMimeType?.startsWith("video/") == true
                    val isFile = receivedFileName != null && !isAudio && !isVideo
                    val storedType = when {
                        isAudio -> StoredMessageType.VOICE
                        isVideo -> StoredMessageType.VIDEO_NOTE
                        isFile -> StoredMessageType.FILE
                        else -> StoredMessageType.TEXT
                    }
                    val senderPeerId = envelope.senderUserId.substringBefore("|").trim()
                    chatHistoryStore.addMessage(senderPeerId, StoredChatMessage(
                        id = envelope.id,
                        text = displaySummary,
                        timestampMs = envelope.timestampMs,
                        isOutgoing = false,
                        type = storedType,
                        fileName = receivedFileName,
                        filePath = receivedFilePath,
                        voiceFileId = if (isAudio) envelope.id else null,
                    ))
                    _incomingMessages.value = (_incomingMessages.value + ReceivedMeshMessage(
                        fromEndpointId = fromEndpointId,
                        envelope = envelope,
                        decryptedText = plainText,
                        accessGranted = canRead && plainText != null,
                        contentKind = content?.kind,
                        contentSummary = displaySummary,
                        receivedFilePath = receivedFilePath,
                        receivedFileName = receivedFileName,
                        receivedFileMimeType = receivedFileMimeType,
                    )).takeLast(500)
                }
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
                trimSeenMessageIds()
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
        if (!forwardingEnabled) {
            log("FWD blocked ${original.id.take(8)}: forwarding disabled")
            return
        }
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
        if (shouldDropOutbound(envelope)) {
            log("DROP outbound ${envelope.type.name} ${envelope.id.take(8)} test=${outboundDropPercent}%")
            return 0
        }
        val bytes = envelope.toJsonBytes()
        if (bytes.size > 32 * 1024) {
            log("TX skipped: payload too large ${bytes.size} bytes (max 32KB)")
            return 0
        }
        val payload = Payload.fromBytes(bytes)
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
                    pendingSentAtMs.remove(msgId)
                    lostCount++
                    queueStore.markFailed(msgId)
                    syncStats()
                    syncDeliveryMetrics()
                    log("Retry timeout ${msgId.take(8)}")
                    break
                }
                val targets = connected.toList()
                if (targets.isEmpty()) continue
                pending.retries++
                queueStore.incrementRetries(msgId)
                sendToTargets(targets, pending.envelope)
                syncStats()
                syncDeliveryMetrics()
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
            syncConnectedPeerInfos()
            val localName = currentEndpointName ?: localIdentity?.userId ?: "unknown-node"
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

    private fun updateRunningState() {
        val wasRunning = _isRunning.value
        val nowRunning = advertisingStarted || discoveryStarted
        _isRunning.value = nowRunning
        if (nowRunning && !wasRunning) {
            startTopologySync()
        }
    }
    
    fun start(identity: LocalIdentity, displayName: String? = null) {
        if (_isRunning.value) return
        localIdentity = identity
        signer.useIdentity(identity.userId)
        knownNodes += identity.userId
        publishTopology()

        advertisingStarted = false
        discoveryStarted = false

        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(strategy)
            .build()
        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(strategy)
            .build()

        // Без pipe в имени, иначе ломается парсинг и лимиты протокола
        val namePart = displayName?.take(30)?.trim()?.replace("|", "_")?.takeIf { it.isNotBlank() }
        currentEndpointName = "${identity.userId}|${Build.MODEL?.take(40) ?: "Android"}${if (namePart != null) "|$namePart" else ""}"
        connectionsClient.startAdvertising(
            currentEndpointName!!,
            serviceId,
            connectionLifecycleCallback,
            advertisingOptions,
        ).addOnSuccessListener {
            advertisingStarted = true
            updateRunningState()
            log("Advertising started as $currentEndpointName")
        }.addOnFailureListener {
            log("Advertising failed: ${it.message}")
            updateRunningState()
        }

        connectionsClient.startDiscovery(
            serviceId,
            discoveryCallback,
            discoveryOptions,
        ).addOnSuccessListener {
            discoveryStarted = true
            updateRunningState()
            log("Discovery started")
        }.addOnFailureListener {
            log("Discovery failed: ${it.message}")
            updateRunningState()
        }
    }

    fun stop() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        topologySyncJob?.cancel()
        topologySyncJob = null
        advertisingStarted = false
        discoveryStarted = false
        currentEndpointName = null
        connected.clear()
        peerNamesByEndpoint.clear()
        knownNodes.clear()
        knownEdges.clear()
        _topology.value = TopologySnapshot()
        _connectedPeerInfos.value = emptyList()
        _realtimeMessages.value = emptyList()
        syncConnected()
        _isRunning.value = false
        pendingAcks.clear()
        syncStats()
        log("Nearby stopped")
    }

    fun broadcast(sender: LocalIdentity, content: OutboundContent) {
        val policy = AccessPolicy()
        val encoded = ContentCodec.encode(content)
        val envelope = buildChatEnvelope(sender, encoded, policy, ttl = 2) ?: return
        sendChat(envelope, encoded.take(50))
    }

    /** Запросить у узла историю чата с нами; ответ придёт как HistoryResponse и будет слит в chatHistoryStore. */
    fun requestHistoryFromPeer(peerId: String, sender: LocalIdentity): Int {
        val request = OutboundContent.HistoryRequest(requestId = java.util.UUID.randomUUID().toString())
        return sendToPeer(peerId, sender, request)
    }

    /**
     * Отправка контента только одному пиру (по userId), как в эталоне 1:1.
     * Используется для звонков и аудио, чтобы не слать всем в mesh.
     */
    fun sendToPeer(peerUserId: String, sender: LocalIdentity, content: OutboundContent): Int {
        val normPeer = peerUserId.substringBefore("|").trim()
        val endpointId = peerNamesByEndpoint.entries.find { it.value == normPeer || it.value.startsWith("$normPeer|") }?.key
            ?: run {
                if (content is OutboundContent.CallSignal) {
                    Log.e("PeerDoneCall", "sendToPeer FAIL: no endpoint for peer normPeer=$normPeer phase=${content.phase} available=${peerNamesByEndpoint.values.take(5)}")
                }
                log("sendToPeer: no endpoint for peer $normPeer")
                return 0
            }
        if (content is OutboundContent.CallSignal) {
            Log.d("PeerDoneCall", "sendToPeer OK phase=${content.phase} toNormPeer=$normPeer endpointFound=true")
        }
        val policy = AccessPolicy()
        val encoded = ContentCodec.encode(content)
        val envelope = buildChatEnvelope(sender, encoded, policy, ttl = 1, recipientUserId = normPeer) ?: return 0
        val signed = signEnvelope(envelope)
        return sendToTargets(listOf(endpointId), signed)
    }

    /**
     * Личная отправка в чат: указываем получателя в конверте и шифруем только для него; TTL обычный (mesh).
     */
    fun sendChatToPeer(peerUserId: String, sender: LocalIdentity, content: OutboundContent, policy: AccessPolicy, ttl: Int): Int {
        val normPeer = peerUserId.substringBefore("|").trim()
        val endpointId = peerNamesByEndpoint.entries.find { it.value == normPeer || it.value.startsWith("$normPeer|") }?.key
            ?: run {
                log("sendChatToPeer: no endpoint for peer $normPeer")
                return 0
            }
        val encoded = ContentCodec.encode(content)
        val envelope = buildChatEnvelope(sender, encoded, policy, ttl, recipientUserId = normPeer) ?: return 0
        val previewText = when (content) {
            is OutboundContent.Text -> content.text
            else -> "[${content.kind}]"
        }
        val signed = signEnvelope(envelope)
        val sizeBytes = signed.toJsonBytes().size.toLong()
        queueStore.upsertQueued(signed.id, previewText, sizeBytes)
        val sent = sendToTargets(listOf(endpointId), signed)
        queueStore.markSent(signed.id)
        seenMessageIds.add(signed.id)
        pendingAcks[signed.id] = PendingAck(envelope = signed)
        sentCount++
        pendingSentAtMs[signed.id] = System.currentTimeMillis()
        if (content !is OutboundContent.HistoryRequest && content !is OutboundContent.HistoryResponse) {
            val storedType = when (content) {
                is OutboundContent.Text -> StoredMessageType.TEXT
                is OutboundContent.FileMeta, is OutboundContent.FileChunk -> StoredMessageType.FILE
                is OutboundContent.VoiceNoteMeta -> StoredMessageType.VOICE
                is OutboundContent.VideoNoteMeta -> StoredMessageType.VIDEO_NOTE
                else -> StoredMessageType.TEXT
            }
            chatHistoryStore.addMessage(normPeer, StoredChatMessage(
                id = signed.id,
                text = previewText,
                timestampMs = signed.timestampMs,
                isOutgoing = true,
                type = storedType,
            ))
        }
        syncStats()
        syncDeliveryMetrics()
        scheduleRetry(signed.id)
        return sent
    }

    fun sendChat(envelope: MeshEnvelope, previewText: String): Int {
        val targets = connected.toList()
        if (targets.isEmpty()) {
            log("No connected peers to send")
            return 0
        }
        val signed = signEnvelope(envelope)
        val sizeBytes = signed.toJsonBytes().size.toLong()
        queueStore.upsertQueued(signed.id, previewText, sizeBytes)
        val sent = sendToTargets(targets, signed)
        queueStore.markSent(signed.id)
        seenMessageIds.add(signed.id)
        pendingAcks[signed.id] = PendingAck(envelope = signed)
        sentCount++
        pendingSentAtMs[signed.id] = System.currentTimeMillis()
        syncStats()
        syncDeliveryMetrics()
        scheduleRetry(signed.id)
        return sent
    }

    fun rememberSentContent(content: OutboundContent) {
        when (content) {
            is OutboundContent.FileMeta -> {
                sentFileMeta[content.fileId] = content
            }
            is OutboundContent.FileChunk -> {
                val bucket = sentFileChunks.getOrPut(content.fileId) { mutableMapOf() }
                bucket[content.chunkIndex] = content
            }
            else -> Unit
        }
    }

    fun buildChatEnvelope(
        sender: LocalIdentity,
        messageText: String,
        policy: AccessPolicy,
        ttl: Int = 3,
        recipientUserId: String? = null,
    ): MeshEnvelope? {
        signer.useIdentity(sender.userId)
        val (keyId, keyBytes) = if (recipientUserId != null) {
            PolicyKeyService.buildSendKeyForRecipient(recipientUserId)
        } else {
            PolicyKeyService.buildSendKey(sender, policy) ?: return null
        }
        val draft = MeshEnvelope.createChat(
            sender = sender,
            senderPublicKeyBase64 = signer.publicKeyBase64(),
            keyId = keyId,
            keyBytes = keyBytes,
            messageText = messageText,
            policy = policy,
            ttl = ttl,
            recipientUserId = recipientUserId,
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

    fun setForwardingEnabled(enabled: Boolean) {
        forwardingEnabled = enabled
        syncTestConfig()
        log("Forwarding: ${if (enabled) "ON" else "OFF"}")
    }

    fun setPacketLossSimulation(inboundPercent: Int, outboundPercent: Int) {
        inboundDropPercent = inboundPercent.coerceIn(0, 90)
        outboundDropPercent = outboundPercent.coerceIn(0, 90)
        syncTestConfig()
        log("Loss simulation in=${inboundDropPercent}% out=${outboundDropPercent}%")
    }

    fun resetDeliveryMetrics() {
        sentCount = 0
        ackReceivedCount = 0
        lostCount = 0
        ackRttsMs.clear()
        syncStats()
        syncDeliveryMetrics()
        log("Delivery metrics reset")
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

    @OptIn(ExperimentalEncodingApi::class)
    private fun buildIncomingSummary(content: OutboundContent?, senderUserId: String): String? {
        return when (content) {
            is OutboundContent.FileMeta -> {
                val old = incomingFiles[content.fileId]
                incomingFiles[content.fileId] = (old ?: IncomingFileState()).copy(meta = content)
                persistIncomingFiles()
                "FILE ${content.fileName}: метаданные получены"
            }
            is OutboundContent.FileChunk -> {
                val bytes = runCatching { Base64.decode(content.chunkBase64) }.getOrNull()
                    ?: return "FILE_CHUNK ${content.fileId}: decode error"
                val prev = incomingFiles[content.fileId] ?: IncomingFileState()
                if (content.chunkIndex !in prev.chunks) {
                    val updatedChunks = prev.chunks.toMutableMap().apply { put(content.chunkIndex, bytes) }
                    val updated = prev.copy(chunks = updatedChunks, chunkTotal = content.chunkTotal)
                    incomingFiles[content.fileId] = updated
                    persistIncomingFiles()
                }
                val state = incomingFiles[content.fileId] ?: return null
                val got = state.chunks.size
                val total = state.chunkTotal ?: content.chunkTotal
                if (got >= total && total > 0) {
                    val assembled = ByteArray(state.chunks.values.sumOf { it.size })
                    var offset = 0
                    state.chunks.toSortedMap().values.forEach { chunk ->
                        chunk.copyInto(assembled, destinationOffset = offset)
                        offset += chunk.size
                    }
                    val hash = sha256Hex(assembled)
                    val fileName = state.meta?.fileName ?: "unknown.bin"
                    val valid = state.meta?.sha256?.equals(hash, ignoreCase = true) ?: false
                    incomingFiles.remove(content.fileId)
                    persistIncomingFiles()
                    if (valid) {
                        val receivedDir = File(appContext.filesDir, "received").apply { mkdirs() }
                        val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                        val savedFile = File(receivedDir, "${content.fileId}_$safeName")
                        savedFile.writeBytes(assembled)
                        completedReceivedFiles[content.fileId] = CompletedFileInfo(
                            fileName = fileName,
                            path = savedFile.absolutePath,
                            mimeType = state.meta?.mimeType,
                        )
                        "FILE $fileName: получен полностью, checksum ok (${assembled.size} bytes)"
                    } else {
                        "FILE $fileName: ошибка целостности (hash mismatch)"
                    }
                } else {
                    val fileName = state.meta?.fileName ?: "file-${content.fileId.take(6)}"
                    maybeRequestMissingChunks(content.fileId, total, got, senderUserId)
                    "FILE $fileName: $got/$total chunks"
                }
            }
            else -> null
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { b -> "%02x".format(b) }
    }

    private fun persistIncomingFiles() {
        val snapshot = incomingFiles.map { (fileId, state) ->
            PersistedIncomingFile(
                fileId = fileId,
                meta = state.meta,
                chunkTotal = state.chunkTotal,
                chunks = state.chunks,
            )
        }
        incomingFileStore.saveAll(snapshot)
    }

    private fun isSenderRateLimited(senderUserId: String): Boolean {
        val now = System.currentTimeMillis()
        val events = inboundSenderEvents.getOrPut(senderUserId) { mutableListOf() }
        synchronized(events) {
            events.removeAll { ts -> now - ts > inboundRateWindowMs }
            if (events.size >= inboundRateLimit) return true
            events += now
            return false
        }
    }

    private fun maybeRequestMissingChunks(fileId: String, total: Int, got: Int, fileSenderUserId: String) {
        if (got <= 0 || got >= total) return
        val now = System.currentTimeMillis()
        val last = repairRequestSentAtMs[fileId] ?: 0L
        if (now - last < 4000L) return
        val state = incomingFiles[fileId] ?: return
        val missing = (0 until total).filter { it !in state.chunks.keys }
        if (missing.isEmpty()) return
        val identity = localIdentity ?: return
        val env = buildChatEnvelope(
            sender = identity,
            messageText = ContentCodec.encode(
                OutboundContent.FileRepairRequest(
                    fileId = fileId,
                    missingIndices = missing.take(20),
                ),
            ),
            policy = AccessPolicy(),
            ttl = 2,
        ) ?: return
        val senderNorm = fileSenderUserId.substringBefore("|").trim()
        val targetEndpoint = peerNamesByEndpoint.entries.find { e ->
            e.value == senderNorm || e.value.startsWith("$senderNorm|")
        }?.key
        val targets = if (targetEndpoint != null) listOf(targetEndpoint) else connected.toList()
        if (targets.isEmpty()) return
        repairRequestSentAtMs[fileId] = now
        sendToTargets(targets, env)
        log("FILE repair request ${fileId.take(6)} missing=${missing.take(20)} -> ${targetEndpoint ?: "broadcast"}")
    }

    private fun handleFileRepairRequest(req: OutboundContent.FileRepairRequest, requesterEndpointId: String) {
        val identity = localIdentity ?: return
        val chunks = sentFileChunks[req.fileId] ?: return
        var resent = 0
        req.missingIndices.forEach { idx ->
            val chunk = chunks[idx] ?: return@forEach
            val env = buildChatEnvelope(
                sender = identity,
                messageText = ContentCodec.encode(chunk),
                policy = AccessPolicy(),
                ttl = 3,
            ) ?: return@forEach
            sendToTargets(listOf(requesterEndpointId), env)
            resent++
        }
        if (resent > 0) {
            log("FILE repaired ${req.fileId.take(6)} resent=$resent -> $requesterEndpointId")
        }
    }

    private fun syncTestConfig() {
        _testConfig.value = NetworkTestConfig(
            forwardingEnabled = forwardingEnabled,
            inboundDropPercent = inboundDropPercent,
            outboundDropPercent = outboundDropPercent,
        )
    }

    private fun shouldDropInbound(envelope: MeshEnvelope): Boolean {
        if (inboundDropPercent <= 0) return false
        if (envelope.type == MeshMessageType.TOPOLOGY) return false
        return Random.nextInt(100) < inboundDropPercent
    }

    private fun shouldDropOutbound(envelope: MeshEnvelope): Boolean {
        if (outboundDropPercent <= 0) return false
        if (envelope.type == MeshMessageType.TOPOLOGY) return false
        return Random.nextInt(100) < outboundDropPercent
    }
}

private fun String?.toSummary(): String {
    if (this == null) return "hidden"
    val decoded = ContentCodec.decode(this) ?: return take(80)
    return when (decoded) {
        is OutboundContent.Text -> decoded.text.take(80)
        is OutboundContent.FileMeta -> "FILE ${decoded.fileName} (${decoded.totalBytes} bytes)"
        is OutboundContent.FileChunk -> "FILE_CHUNK ${decoded.fileId} ${decoded.chunkIndex + 1}/${decoded.chunkTotal}"
        is OutboundContent.FileRepairRequest -> "FILE_REPAIR ${decoded.fileId.take(8)} miss=${decoded.missingIndices.size}"
        is OutboundContent.VoiceNoteMeta -> "VOICE ${decoded.durationMs}ms ${decoded.codec}"
        is OutboundContent.VideoNoteMeta -> "VIDEO_NOTE ${decoded.durationMs}ms ${decoded.width}x${decoded.height}"
        is OutboundContent.CallSignal -> "CALL_SIGNAL ${decoded.phase} ${decoded.callId.take(8)}"
        is OutboundContent.AudioPacket -> "AUDIO_PACKET seq=${decoded.sequenceNumber} ${decoded.callId.take(8)}"
        is OutboundContent.HistoryRequest -> "HISTORY_REQUEST ${decoded.requestId.take(8)}"
        is OutboundContent.HistoryResponse -> "HISTORY_RESPONSE ${decoded.requestId.take(8)} ${decoded.messages.size} msgs"
    }
}

