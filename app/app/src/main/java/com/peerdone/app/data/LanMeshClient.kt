package com.peerdone.app.data

import android.os.Build
import android.util.Log
import com.peerdone.app.core.message.ContentCodec
import java.io.File
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
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
import com.peerdone.app.domain.PolicyEngine
import com.peerdone.app.domain.PolicyKeyService
import com.peerdone.app.domain.UserDirectoryEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private const val DISCOVERY_PORT = 39888
private const val TCP_PORT = 39889
private const val TAG = "LanMesh"
private const val MAX_PACKET_BYTES = 256 * 1024
private const val PEER_TIMEOUT_MS = 10_000L

/**
 * LAN mesh-клиент, совместимый с iOS [LanMeshService]:
 * - UDP broadcast на порту 39888 для discovery (hello: userId, nickname, device, tcpPort, ...)
 * - TCP на порту 39889: кадр = 4 байта BE длина + JSON [MeshEnvelope]
 * Подпись и шифрование — как на iOS (signingString без recipientUserId, policy.signingJSON).
 */
class LanMeshClient(
    private val context: android.content.Context,
    val chatHistoryStore: ChatHistoryStore,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val signer = DeviceKeyStoreSigner()
    private val identityTrustStore = IdentityTrustStore(appContext)
    private val queueStore = MessageQueueStore(appContext)

    private var localIdentity: LocalIdentity? = null
    private var currentDisplayName: String? = null
    private val running = AtomicBoolean(false)
    private var udpSocket: DatagramSocket? = null
    private var tcpServer: ServerSocket? = null
    private var timerJob: Job? = null

    /** userId -> (host, port, nickname, device, lastSeenMs) */
    private val peersByUserId = ConcurrentHashMap<String, LanPeer>()
    private val seenMessageIds = Collections.synchronizedSet(mutableSetOf<String>())
    private val pendingAcks = ConcurrentHashMap<String, PendingAck>()
    private val envelopeIdToReceivedFrom = ConcurrentHashMap<String, String>()
    private val knownNodes = Collections.synchronizedSet(mutableSetOf<String>())
    private val knownEdges = Collections.synchronizedSet(mutableSetOf<TopologyEdge>())
    /** Косвенные пиры (из TOPOLOGY): userId -> userId реле (прямой пир, через которого слать). */
    private val relayPeerByIndirectUserId = ConcurrentHashMap<String, String>()
    private var topologySyncJob: Job? = null

    /** В режиме «все протоколы» — объединённый список userId по всем транспортам для TOPOLOGY. */
    @Volatile
    var topologyPeerListProvider: (() -> List<String>)? = null
    private var sentCount = 0
    private var ackReceivedCount = 0
    private var lostCount = 0
    private val ackRttsMs = mutableListOf<Long>()
    private val pendingSentAtMs = ConcurrentHashMap<String, Long>()
    private var dropAcksForDemo = false
    private var forwardingEnabled = true

    private data class IncomingFileState(
        val meta: OutboundContent.FileMeta? = null,
        val chunks: MutableMap<Int, ByteArray> = mutableMapOf(),
        var chunkTotal: Int? = null,
    )

    private data class CompletedFileInfo(
        val fileName: String,
        val path: String,
        val mimeType: String?,
    )

    private val incomingFiles = ConcurrentHashMap<String, IncomingFileState>()
    private val completedReceivedFiles = ConcurrentHashMap<String, CompletedFileInfo>()

    private data class LanPeer(
        val host: String,
        val port: Int,
        val nickname: String,
        val device: String,
        var lastSeenMs: Long,
    )

    private data class PendingAck(val envelope: MeshEnvelope, var retries: Int = 0)

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _connectedPeerInfos = MutableStateFlow<List<NearbyMeshClient.PeerInfo>>(emptyList())
    val connectedPeerInfos: StateFlow<List<NearbyMeshClient.PeerInfo>> = _connectedPeerInfos.asStateFlow()

    private val _connectedEndpoints = MutableStateFlow<List<String>>(emptyList())
    val connectedEndpoints: StateFlow<List<String>> = _connectedEndpoints.asStateFlow()

    private val _incomingMessages = MutableStateFlow<List<ReceivedMeshMessage>>(emptyList())
    val incomingMessages: StateFlow<List<ReceivedMeshMessage>> = _incomingMessages.asStateFlow()

    private val _realtimeMessages = MutableStateFlow<List<ReceivedMeshMessage>>(emptyList())
    val realtimeMessages: StateFlow<List<ReceivedMeshMessage>> = _realtimeMessages.asStateFlow()

    private val _topology = MutableStateFlow(TopologySnapshot())
    val topology: StateFlow<TopologySnapshot> = _topology.asStateFlow()

    private val _deliveryMetrics = MutableStateFlow(DeliveryMetrics())
    val deliveryMetrics: StateFlow<DeliveryMetrics> = _deliveryMetrics.asStateFlow()

    private val _stats = MutableStateFlow(MeshStats())
    val stats: StateFlow<MeshStats> = _stats.asStateFlow()

    private val _testConfig = MutableStateFlow(NetworkTestConfig())
    val testConfig: StateFlow<NetworkTestConfig> = _testConfig.asStateFlow()

    val outboundRecords: StateFlow<List<OutboundMessageRecord>> = queueStore.records

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private fun log(line: String) {
        Log.d(TAG, line)
        _logs.value = (_logs.value + line).takeLast(120)
    }

    private fun syncPeerInfos() {
        val me = localIdentity?.userId?.substringBefore("|")?.trim() ?: ""
        val direct = peersByUserId.map { (userId, peer) ->
            NearbyMeshClient.PeerInfo(
                endpointId = userId,
                userId = userId,
                deviceModel = peer.device,
                displayName = peer.nickname.ifBlank { userId },
            )
        }
        val indirect = knownNodes.filter { it != me && !peersByUserId.containsKey(it) }.map { userId ->
            NearbyMeshClient.PeerInfo(
                endpointId = userId,
                userId = userId,
                deviceModel = "",
                displayName = "$userId (через сеть)",
            )
        }
        _connectedPeerInfos.value = direct + indirect
        _connectedEndpoints.value = (peersByUserId.keys + relayPeerByIndirectUserId.keys).toList()
    }

    private fun syncStats() {
        _stats.value = MeshStats(
            seenCount = seenMessageIds.size,
            droppedDuplicates = 0,
            forwardedCount = 0,
            pendingAckCount = pendingAcks.size,
            ackReceivedCount = ackReceivedCount,
        )
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

    private fun publishTopology() {
        val me = localIdentity?.userId ?: return
        val nodes = (knownNodes + me).toList().sorted()
        val edges = knownEdges.toList().sortedWith(compareBy({ it.fromNode }, { it.toNode }))
        _topology.value = TopologySnapshot(nodes = nodes, edges = edges, updatedAtMs = System.currentTimeMillis())
    }

    fun start(identity: LocalIdentity, displayName: String? = null) {
        if (running.getAndSet(true)) return
        localIdentity = identity
        currentDisplayName = displayName
        signer.useIdentity(identity.userId)
        knownNodes.add(identity.userId)
        publishTopology()

        try {
            val udp = DatagramSocket(DISCOVERY_PORT).apply {
                reuseAddress = true
                broadcast = true
            }
            udpSocket = udp

            val tcp = ServerSocket(TCP_PORT).apply { reuseAddress = true }
            tcpServer = tcp

            _isRunning.value = true
            log("LAN mesh started UDP:$DISCOVERY_PORT TCP:$TCP_PORT")

            scope.launch {
                while (isActive && running.get()) {
                    try {
                        val buf = ByteArray(2048)
                        val packet = DatagramPacket(buf, buf.size)
                        udp.receive(packet)
                        val data = packet.data.copyOfRange(0, packet.length)
                        handleUdpPacket(data, packet.address.hostAddress ?: "")
                    } catch (e: Exception) {
                        if (running.get()) log("UDP read error: ${e.message}")
                    }
                }
            }

            scope.launch {
                while (isActive && running.get()) {
                    try {
                        val client = tcp.accept()
                        scope.launch(Dispatchers.IO) {
                            handleTcpClient(client)
                        }
                    } catch (e: Exception) {
                        if (running.get()) log("TCP accept error: ${e.message}")
                    }
                }
            }

            timerJob = scope.launch {
                sendHello()
                while (isActive && running.get()) {
                    delay(2000)
                    sendHello()
                    retryPendingAcks()
                    cleanupPeers()
                    updateLocalTopology()
                    syncPeerInfos()
                    syncStats()
                    syncDeliveryMetrics()
                }
            }

            topologySyncJob = scope.launch {
                while (isActive && running.get()) {
                    delay(4000)
                    sendTopologyUpdate()
                }
            }

            sendHello()
            sendTopologyUpdate()
        } catch (e: Exception) {
            log("LAN start failed: ${e.message}")
            stop()
        }
    }

    private fun sendHello() {
        val identity = localIdentity ?: return
        val deviceModel = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ").trim().take(40)
        val nickname = (currentDisplayName?.trim()?.take(30))?.takeIf { it.isNotBlank() }
            ?: deviceModel.ifBlank { "Android" }
        val payload = JSONObject().apply {
            put("type", "hello")
            put("userId", identity.userId)
            put("orgId", identity.orgId)
            put("level", identity.level)
            put("nickname", nickname)
            put("device", deviceModel.ifBlank { "Android" })
            put("tcpPort", TCP_PORT)
            put("timestampMs", System.currentTimeMillis())
        }
        val data = payload.toString().encodeToByteArray()
        val targets = broadcastTargets()
        targets.forEach { host ->
            try {
                val addr = InetAddress.getByName(host)
                val packet = DatagramPacket(data, data.size, addr, DISCOVERY_PORT)
                udpSocket?.send(packet)
            } catch (e: Exception) {
                if (running.get()) log("UDP send error to $host: ${e.message}")
            }
        }
    }

    private fun broadcastTargets(): List<String> {
        val hosts = mutableSetOf("255.255.255.255")
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { iface ->
                if (!iface.isUp || iface.isLoopback) return@forEach
                iface.interfaceAddresses?.forEach { ifAddr ->
                    ifAddr.broadcast?.hostAddress?.let { hosts.add(it) }
                }
            }
        } catch (_: Exception) {}
        return hosts.toList()
    }

    private fun handleUdpPacket(data: ByteArray, fromHost: String) {
        runCatching {
            val json = JSONObject(data.decodeToString())
            if (json.optString("type") != "hello") return@runCatching
            val userId = json.optString("userId").takeIf { it.isNotBlank() } ?: return@runCatching
            val identity = localIdentity ?: return@runCatching
            if (userId == identity.userId) return@runCatching
            val tcpPort = json.optInt("tcpPort", TCP_PORT).takeIf { it in 1..65535 } ?: return@runCatching
            val nickname = json.optString("nickname").trim().ifBlank { userId }
            val device = json.optString("device", "unknown")
            peersByUserId[userId] = LanPeer(
                host = fromHost,
                port = tcpPort,
                nickname = nickname,
                device = device,
                lastSeenMs = System.currentTimeMillis(),
            )
            syncPeerInfos()
        }
    }

    private fun handleTcpClient(client: Socket) {
        try {
            client.soTimeout = 15000
            val input = client.getInputStream()
            val lenBuf = ByteArray(4)
            if (input.read(lenBuf) != 4) return
            val length = ByteBuffer.wrap(lenBuf).int and 0x7FFFFFFF
            if (length <= 0 || length > MAX_PACKET_BYTES) return
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n <= 0) return
                read += n
            }
            val envelope = MeshEnvelope.fromJsonBytes(body)
            val fromId = envelope.senderUserId
            scope.launch(Dispatchers.IO) {
                handleEnvelope(fromId, envelope)
            }
        } catch (e: Exception) {
            log("TCP read error: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun buildIncomingSummary(content: OutboundContent?, senderUserId: String): String? {
        if (content == null) return null
        return when (content) {
            is OutboundContent.FileMeta -> {
                val old = incomingFiles[content.fileId]
                incomingFiles[content.fileId] = (old ?: IncomingFileState()).copy(meta = content)
                "FILE ${content.fileName}: метаданные получены"
            }
            is OutboundContent.FileChunk -> {
                val bytes = runCatching { Base64.decode(content.chunkBase64) }.getOrNull()
                    ?: return "FILE_CHUNK ${content.fileId}: decode error"
                val prev = incomingFiles[content.fileId] ?: IncomingFileState()
                if (content.chunkIndex !in prev.chunks) {
                    prev.chunks[content.chunkIndex] = bytes
                    prev.chunkTotal = content.chunkTotal
                    incomingFiles[content.fileId] = prev
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
                        "FILE $fileName: получен (${assembled.size} bytes)"
                    } else {
                        "FILE $fileName: ошибка целостности"
                    }
                } else {
                    val fileName = state.meta?.fileName ?: "file-${content.fileId.take(6)}"
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

    private fun updatePeerLastSeen(userId: String) {
        peersByUserId[userId]?.let { peer ->
            peersByUserId[userId] = peer.copy(lastSeenMs = System.currentTimeMillis())
        }
    }

    private fun handleEnvelope(fromEndpointId: String, envelope: MeshEnvelope) {
        updatePeerLastSeen(envelope.senderUserId)
        if (!MeshSignature.verify(envelope)) {
            log("drop ${envelope.id.take(8)} invalid signature")
            return
        }
        val trustOk = identityTrustStore.validateOrRemember(
            userId = envelope.senderUserId,
            orgId = envelope.senderOrgId,
            level = envelope.senderLevel,
            senderPublicKeyBase64 = envelope.senderPublicKeyBase64,
        )
        if (!trustOk) {
            log("drop ${envelope.id.take(8)} trust mismatch ${envelope.senderUserId}")
            return
        }

        when (envelope.type) {
            MeshMessageType.ACK -> {
                val ackFor = envelope.ackForId ?: return
                if (pendingAcks.remove(ackFor) != null) {
                    ackReceivedCount++
                    pendingSentAtMs.remove(ackFor)?.let { startedAt ->
                        val rtt = (System.currentTimeMillis() - startedAt).coerceAtLeast(0)
                        ackRttsMs.add(rtt)
                        if (ackRttsMs.size > 300) ackRttsMs.removeAt(0)
                    }
                    queueStore.markAcked(ackFor)
                    syncStats()
                    syncDeliveryMetrics()
                    log("ACK for ${ackFor.take(8)} from ${envelope.senderUserId}")
                } else {
                    envelopeIdToReceivedFrom.remove(ackFor)?.let { originalSender ->
                        val peer = peersByUserId[originalSender]
                        if (peer != null) sendPacketTo(peer.host, peer.port, envelope.toJsonBytes())
                    }
                }
            }

            MeshMessageType.CHAT -> {
                if (!seenMessageIds.add(envelope.id)) return
                envelopeIdToReceivedFrom[envelope.id] = fromEndpointId
                syncStats()

                val recipient = envelope.recipientUserId?.substringBefore("|")?.trim()
                val identity = localIdentity
                val iAmRecipient = recipient != null && identity != null && identity.userId.substringBefore("|").trim() == recipient
                if (recipient != null && !iAmRecipient) {
                    if (envelope.ttl > 0) forward(envelope, fromEndpointId)
                    return
                }

                val canRead = when {
                    identity == null -> false
                    recipient != null -> iAmRecipient
                    else -> PolicyEngine.matches(
                        user = UserDirectoryEntry(identity.userId, identity.orgId, identity.level, true),
                        policy = envelope.policy,
                    )
                }

                val plainText = if (canRead) {
                    val key = identity?.let { PolicyKeyService.resolveReadKey(it, envelope.keyId) }
                    key?.let { MeshCrypto.decryptWithKey(envelope.ivBase64, envelope.cipherTextBase64, it) }
                } else null

                val content = plainText?.let { ContentCodec.decode(it) }

                if (content is OutboundContent.CallSignal || content is OutboundContent.AudioPacket) {
                    _realtimeMessages.value = (_realtimeMessages.value + ReceivedMeshMessage(
                        fromEndpointId, envelope, plainText, canRead && plainText != null, content.kind, "",
                    )).takeLast(500)
                    if (!dropAcksForDemo) sendAck(fromEndpointId, envelope.id)
                    return
                }

                if (content is OutboundContent.HistoryRequest && identity != null) {
                    val senderPeerId = envelope.senderUserId.substringBefore("|").trim()
                    val ourMessages = chatHistoryStore.getMessagesForPeerSync(senderPeerId)
                    val items = ourMessages.map { m ->
                        HistoryResponseItem(m.id, m.text, m.timestampMs, m.isOutgoing, m.type.name, m.fileName, m.filePath, m.voiceFileId)
                    }
                    val response = OutboundContent.HistoryResponse(content.requestId, items)
                    sendChatToPeer(senderPeerId, identity, response, AccessPolicy(), ttl = 1)
                    if (!dropAcksForDemo) sendAck(fromEndpointId, envelope.id)
                    return
                }
                if (content is OutboundContent.HistoryResponse) {
                    val senderPeerId = envelope.senderUserId.substringBefore("|").trim()
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

                // Сборка файлов: FileMeta + FileChunk -> полный файл в completedReceivedFiles
                val enrichedSummary = if (content is OutboundContent.FileMeta || content is OutboundContent.FileChunk) {
                    buildIncomingSummary(content, envelope.senderUserId)
                } else null
                val completedFile = (content as? OutboundContent.FileChunk)?.let { chunk ->
                    completedReceivedFiles[chunk.fileId]
                }
                val receivedFilePath = completedFile?.path
                val receivedFileName = completedFile?.fileName
                val receivedFileMimeType = completedFile?.mimeType

                val shouldAddToChat = content !is OutboundContent.FileMeta &&
                    content !is OutboundContent.FileRepairRequest &&
                    (content !is OutboundContent.FileChunk || receivedFilePath != null)

                val summary = when {
                    receivedFilePath != null && receivedFileMimeType?.startsWith("audio/") == true -> "Голосовое сообщение"
                    receivedFilePath != null && receivedFileMimeType?.startsWith("video/") == true -> "Видео"
                    receivedFileName != null -> receivedFileName
                    content is OutboundContent.Text -> content.text.take(80)
                    content is OutboundContent.VoiceNoteMeta -> "Голосовое сообщение"
                    content is OutboundContent.VideoNoteMeta -> "Видео"
                    else -> enrichedSummary ?: "[${content?.kind?.name ?: "?"}]"
                }
                val senderPeerId = envelope.senderUserId.substringBefore("|").trim()
                fun norm(s: String) = s.substringBefore("|").trim()
                val isFileOrVoiceCompletion = receivedFilePath != null
                val alreadyHaveSameFile = isFileOrVoiceCompletion && _incomingMessages.value.any {
                    norm(it.envelope.senderUserId) == norm(envelope.senderUserId) && it.receivedFilePath == receivedFilePath
                }

                if (shouldAddToChat && !alreadyHaveSameFile && canRead && plainText != null) {
                    val isAudio = receivedFilePath != null && receivedFileMimeType?.startsWith("audio/") == true
                    val isVideo = receivedFilePath != null && receivedFileMimeType?.startsWith("video/") == true
                    val isFile = receivedFileName != null && !isAudio && !isVideo
                    val storedType = when {
                        isAudio -> StoredMessageType.VOICE
                        isVideo -> StoredMessageType.VIDEO_NOTE
                        isFile -> StoredMessageType.FILE
                        else -> StoredMessageType.TEXT
                    }
                    chatHistoryStore.addMessage(senderPeerId, StoredChatMessage(
                        id = envelope.id,
                        text = summary,
                        timestampMs = envelope.timestampMs,
                        isOutgoing = false,
                        type = storedType,
                        fileName = receivedFileName,
                        filePath = receivedFilePath,
                        voiceFileId = if (isAudio) envelope.id else null,
                    ))
                    _incomingMessages.value = (_incomingMessages.value + ReceivedMeshMessage(
                        fromEndpointId, envelope, plainText, true,
                        content?.kind, summary,
                        receivedFilePath = receivedFilePath,
                        receivedFileName = receivedFileName,
                        receivedFileMimeType = receivedFileMimeType,
                    )).takeLast(500)
                } else if (!shouldAddToChat && enrichedSummary != null) {
                    log("FILE: $enrichedSummary")
                }
                if (!dropAcksForDemo) sendAck(fromEndpointId, envelope.id)
                if (envelope.ttl > 0) forward(envelope, fromEndpointId)
            }

            MeshMessageType.TOPOLOGY -> {
                if (!seenMessageIds.add(envelope.id)) return
                runCatching {
                    val payload = MeshCrypto.decryptControl(envelope.ivBase64, envelope.cipherTextBase64)
                    val json = JSONObject(payload)
                    val src = json.getString("sourceNode")
                    val peers = json.optJSONArray("directPeers") ?: org.json.JSONArray()
                    knownNodes.add(src)
                    for (i in 0 until peers.length()) {
                        val peer = peers.getString(i)
                        knownNodes.add(peer)
                        knownEdges.add(TopologyEdge(src, peer))
                        knownEdges.add(TopologyEdge(peer, src))
                        if (peersByUserId.containsKey(src) && peer != localIdentity?.userId?.substringBefore("|")?.trim()) {
                            relayPeerByIndirectUserId[peer] = src
                        }
                    }
                    publishTopology()
                    syncPeerInfos()
                }
                if (envelope.ttl > 0) forward(envelope, fromEndpointId)
            }
        }
    }

    private fun sendAck(toUserId: String, msgId: String) {
        val sender = localIdentity ?: return
        val ackDraft = MeshEnvelope.createAck(
            sender = sender,
            senderPublicKeyBase64 = signer.publicKeyBase64(),
            ackForId = msgId,
        )
        val ack = signEnvelope(ackDraft)
        val peer = peersByUserId[toUserId] ?: return
        sendPacketTo(peer.host, peer.port, ack.toJsonBytes())
    }

    private fun forward(original: MeshEnvelope, fromUserId: String) {
        if (!forwardingEnabled) return
        val forwarded = original.forForwarding(
            newTtl = (original.ttl - 1).coerceAtLeast(0),
            newHopCount = original.hopCount + 1,
        )
        val recipient = original.recipientUserId?.substringBefore("|")?.trim()
        val targets = if (recipient != null && recipient != localIdentity?.userId?.substringBefore("|")?.trim() && peersByUserId.containsKey(recipient)) {
            listOf(recipient)
        } else {
            peersByUserId.keys.filter { it != fromUserId }
        }
        targets.forEach { userId ->
            val peer = peersByUserId[userId] ?: return@forEach
            sendPacketTo(peer.host, peer.port, forwarded.toJsonBytes())
        }
    }

    private fun sendPacketTo(host: String, port: Int, data: ByteArray): Boolean {
        if (data.size > 32 * 1024) return false
        return try {
            Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(host, port), 5000)
                socket.soTimeout = 5000
                val out = socket.getOutputStream()
                val lenBuf = ByteBuffer.allocate(4).putInt(data.size).array()
                out.write(lenBuf)
                out.write(data)
                out.flush()
            }
            true
        } catch (e: Exception) {
            log("send failed $host:$port ${e.message}")
            false
        }
    }

    private fun sendToTargets(userIds: List<String>, envelope: MeshEnvelope): Int {
        if (userIds.isEmpty()) return 0
        val bytes = envelope.toJsonBytes()
        var sent = 0
        userIds.forEach { userId ->
            val peer = peersByUserId[userId] ?: return@forEach
            if (sendPacketTo(peer.host, peer.port, bytes)) sent++
        }
        return sent
    }

    private fun retryPendingAcks() {
        val now = System.currentTimeMillis()
        pendingAcks.entries.toList().forEach { (msgId, pending) ->
            if (now - (pendingSentAtMs[msgId] ?: 0) < 2500) return@forEach
            if (pending.retries >= 2) {
                pendingAcks.remove(msgId)
                pendingSentAtMs.remove(msgId)
                lostCount++
                queueStore.markFailed(msgId)
                syncStats()
                syncDeliveryMetrics()
                return@forEach
            }
            pending.retries++
            queueStore.incrementRetries(msgId)
            val peerList = peersByUserId.keys.toList()
            sendToTargets(peerList, pending.envelope)
        }
    }

    private fun cleanupPeers() {
        val now = System.currentTimeMillis()
        peersByUserId.entries.removeIf { (_, p) -> now - p.lastSeenMs > PEER_TIMEOUT_MS }
    }

    private fun updateLocalTopology() {
        val me = localIdentity?.userId ?: return
        knownNodes.add(me)
        peersByUserId.keys.forEach { userId ->
            knownNodes.add(userId)
            knownEdges.add(TopologyEdge(me, userId))
            knownEdges.add(TopologyEdge(userId, me))
        }
        publishTopology()
    }

    private fun buildTopologyPayload(): String {
        val me = localIdentity?.userId ?: "unknown"
        val peers = topologyPeerListProvider?.invoke() ?: peersByUserId.keys.toList()
        return JSONObject()
            .put("sourceNode", me)
            .put("directPeers", org.json.JSONArray(peers))
            .put("timestampMs", System.currentTimeMillis())
            .toString()
    }

    private fun sendTopologyUpdate() {
        val sender = localIdentity ?: return
        val targets = peersByUserId.keys.toList()
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

    fun stop() {
        if (!running.getAndSet(false)) return
        timerJob?.cancel()
        try { udpSocket?.close() } catch (_: Exception) {}
        udpSocket = null
        try { tcpServer?.close() } catch (_: Exception) {}
        tcpServer = null
        peersByUserId.clear()
        seenMessageIds.clear()
        pendingAcks.clear()
        envelopeIdToReceivedFrom.clear()
        incomingFiles.clear()
        completedReceivedFiles.clear()
        knownNodes.clear()
        knownEdges.clear()
        relayPeerByIndirectUserId.clear()
        topologySyncJob?.cancel()
        topologySyncJob = null
        _isRunning.value = false
        _connectedPeerInfos.value = emptyList()
        _connectedEndpoints.value = emptyList()
        _incomingMessages.value = emptyList()
        _realtimeMessages.value = emptyList()
        syncStats()
        syncDeliveryMetrics()
        log("LAN mesh stopped")
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
        return draft.copy(signatureBase64 = signer.sign(draft.signingString()))
    }

    fun sendChat(envelope: MeshEnvelope, previewText: String): Int {
        val targets = peersByUserId.keys.toList()
        if (targets.isEmpty()) {
            log("No peers to send")
            return 0
        }
        val signed = signEnvelope(envelope)
        val sizeBytes = signed.toJsonBytes().size.toLong()
        queueStore.upsertQueued(signed.id, previewText, sizeBytes)
        val sent = sendToTargets(targets, signed)
        queueStore.markSent(signed.id)
        seenMessageIds.add(signed.id)
        pendingAcks[signed.id] = PendingAck(envelope = signed)
        pendingSentAtMs[signed.id] = System.currentTimeMillis()
        sentCount++
        // Чтобы отправленное сообщение отображалось в чате у отправителя
        targets.forEach { peerId ->
            chatHistoryStore.addMessage(peerId, StoredChatMessage(
                id = signed.id,
                text = previewText,
                timestampMs = signed.timestampMs,
                isOutgoing = true,
                type = StoredMessageType.TEXT,
                fileName = null,
                filePath = null,
                voiceFileId = null,
            ))
        }
        syncStats()
        syncDeliveryMetrics()
        scope.launch {
            repeat(2) {
                delay(2500)
                if (!pendingAcks.containsKey(signed.id)) return@launch
                retryPendingAcks()
            }
        }
        return sent
    }

    fun sendToPeer(peerUserId: String, sender: LocalIdentity, content: OutboundContent): Int {
        val normPeer = peerUserId.substringBefore("|").trim()
        val target = if (peersByUserId.containsKey(normPeer)) normPeer else relayPeerByIndirectUserId[normPeer]
        if (target == null) {
            log("sendToPeer: no peer $normPeer (direct or via relay)")
            return 0
        }
        val policy = AccessPolicy()
        val encoded = ContentCodec.encode(content)
        val envelope = buildChatEnvelope(sender, encoded, policy, ttl = 1, recipientUserId = normPeer) ?: return 0
        val signed = signEnvelope(envelope)
        return sendToTargets(listOf(target), signed)
    }

    fun sendChatToPeer(peerUserId: String, sender: LocalIdentity, content: OutboundContent, policy: AccessPolicy, ttl: Int): Int {
        val normPeer = peerUserId.substringBefore("|").trim()
        val sendToPeerId = if (peersByUserId.containsKey(normPeer)) normPeer else relayPeerByIndirectUserId[normPeer]
        if (sendToPeerId == null) {
            log("sendChatToPeer: no peer $normPeer (direct or via relay)")
            return 0
        }
        val encoded = ContentCodec.encode(content)
        val envelope = buildChatEnvelope(sender, encoded, policy, ttl, recipientUserId = normPeer) ?: return 0
        val previewText = when (content) {
            is OutboundContent.Text -> content.text
            is OutboundContent.FileMeta -> "Файл: ${content.fileName}"
            is OutboundContent.VoiceNoteMeta -> "Голосовое сообщение"
            is OutboundContent.VideoNoteMeta -> "Видео"
            else -> "[${content.kind}]"
        }
        val signed = signEnvelope(envelope)
        queueStore.upsertQueued(signed.id, previewText, signed.toJsonBytes().size.toLong())
        val sent = sendToTargets(listOf(sendToPeerId), signed)
        queueStore.markSent(signed.id)
        seenMessageIds.add(signed.id)
        pendingAcks[signed.id] = PendingAck(envelope = signed)
        pendingSentAtMs[signed.id] = System.currentTimeMillis()
        sentCount++
        val storedType = when (content) {
            is OutboundContent.Text -> StoredMessageType.TEXT
            is OutboundContent.FileMeta -> StoredMessageType.FILE
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
            fileName = (content as? OutboundContent.FileMeta)?.fileName,
            filePath = null,
            voiceFileId = null,
        ))
        syncStats()
        syncDeliveryMetrics()
        scope.launch {
            repeat(2) {
                delay(2500)
                if (!pendingAcks.containsKey(signed.id)) return@launch
                retryPendingAcks()
            }
        }
        return sent
    }

    fun broadcast(sender: LocalIdentity, content: OutboundContent) {
        val policy = AccessPolicy()
        val encoded = ContentCodec.encode(content)
        val envelope = buildChatEnvelope(sender, encoded, policy, ttl = 2) ?: return
        sendChat(envelope, encoded.take(50).toString())
    }

    fun rememberSentContent(content: OutboundContent) = Unit

    fun requestHistoryFromPeer(peerId: String, sender: LocalIdentity): Int {
        return sendToPeer(peerId, sender, OutboundContent.HistoryRequest(requestId = java.util.UUID.randomUUID().toString()))
    }

    fun setDropAcksForDemo(enabled: Boolean) { dropAcksForDemo = enabled }
    fun setForwardingEnabled(enabled: Boolean) { forwardingEnabled = enabled }
    fun setPacketLossSimulation(inboundPercent: Int, outboundPercent: Int) = Unit
    fun resetDeliveryMetrics() {
        sentCount = 0
        ackReceivedCount = 0
        lostCount = 0
        ackRttsMs.clear()
        syncStats()
        syncDeliveryMetrics()
    }

    fun transportHealth(): TransportHealth {
        val peers = peersByUserId.size
        val available = _isRunning.value && peers > 0
        return TransportHealth(
            type = TransportType.LAN_P2P,
            available = available,
            estimatedLatencyMs = if (peers == 0) 999 else 50,
            estimatedBandwidthKbps = if (peers == 0) 0 else 10000,
            estimatedBatteryCost = if (_isRunning.value) 4 else 1,
            stabilityScore = if (peers == 0) 2 else 8,
        )
    }
}
