package com.peerdone.app.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import com.peerdone.app.core.message.ContentCodec
import com.peerdone.app.core.message.ContentKind
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private const val WIFI_DIRECT_PORT = 45455
private const val TAG = "WifiDirectMesh"

/**
 * WiFi Direct транспорт: обнаружение через [WifiP2pManager.discoverPeers()] (без Bluetooth),
 * подключение 1:1, обмен конвертами [MeshEnvelope] по TCP-сокету.
 * На Android устройство может быть только в одной P2P-группе, поэтому одновременно — один пир.
 */
class WifiDirectMeshClient(
    private val context: Context,
) {
    private val appContext = context.applicationContext
    private val manager: WifiP2pManager? = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel: WifiP2pManager.Channel? = manager?.initialize(appContext, appContext.mainLooper, null)
    private val signer = DeviceKeyStoreSigner()
    private val identityTrustStore = IdentityTrustStore(appContext)
    val chatHistoryStore = ChatHistoryStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var localIdentity: LocalIdentity? = null
    private val connected = Collections.synchronizedSet(linkedSetOf<String>())
    private val peerNamesByEndpoint = ConcurrentHashMap<String, String>()
    private val peerSocketByEndpoint = ConcurrentHashMap<String, Socket>()
    private var serverSocket: ServerSocket? = null
    private val discoveryRunning = AtomicBoolean(false)
    private val seenMessageIds = Collections.synchronizedSet(linkedSetOf<String>())

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _connectedPeerInfos = MutableStateFlow<List<NearbyMeshClient.PeerInfo>>(emptyList())
    val connectedPeerInfos: StateFlow<List<NearbyMeshClient.PeerInfo>> = _connectedPeerInfos.asStateFlow()

    private val _incomingMessages = MutableStateFlow<List<ReceivedMeshMessage>>(emptyList())
    val incomingMessages: StateFlow<List<ReceivedMeshMessage>> = _incomingMessages.asStateFlow()

    private val _realtimeMessages = MutableStateFlow<List<ReceivedMeshMessage>>(emptyList())
    val realtimeMessages: StateFlow<List<ReceivedMeshMessage>> = _realtimeMessages.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    channel?.let { ch ->
                        manager?.requestPeers(ch) { peers ->
                            peers?.deviceList?.filter { it.status == WifiP2pDevice.CONNECTED }?.forEach { device ->
                                // Уже подключён — не дублируем
                                if (device.deviceAddress in connected) return@forEach
                                connectToDevice(ch, device)
                            }
                            peers?.deviceList?.filter { it.status != WifiP2pDevice.CONNECTED }?.forEach { device ->
                                connectToDevice(ch, device)
                            }
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    if (networkInfo?.isConnected == true) {
                        channel?.let { ch ->
                            manager?.requestConnectionInfo(ch) { info ->
                                if (info == null) return@requestConnectionInfo
                                scope.launch {
                                    if (info.isGroupOwner) {
                                        startServerSocket()
                                    } else {
                                        info.groupOwnerAddress?.let { addr ->
                                            connectToGroupOwner(addr.hostAddress ?: return@let)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun log(line: String) {
        Log.d(TAG, line)
        _logs.value = (_logs.value + line).takeLast(100)
    }

    private fun connectToDevice(ch: WifiP2pManager.Channel, device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        manager?.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = log("WifiDirect connect requested to ${device.deviceAddress}")
            override fun onFailure(reason: Int) = log("WifiDirect connect failed: $reason")
        })
    }

    private fun startServerSocket() {
        if (serverSocket != null) return
        try {
            val server = ServerSocket(WIFI_DIRECT_PORT)
            serverSocket = server
            log("WifiDirect ServerSocket listening on $WIFI_DIRECT_PORT")
            scope.launch(Dispatchers.IO) {
                while (true) {
                    val client = try {
                        server.accept()
                    } catch (e: Exception) {
                        break
                    }
                    handleNewSocket(client, isServer = true)
                }
            }
        } catch (e: Exception) {
            log("WifiDirect ServerSocket error: ${e.message}")
        }
    }

    private fun connectToGroupOwner(host: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, WIFI_DIRECT_PORT), 10000)
                handleNewSocket(socket, isServer = false)
            } catch (e: Exception) {
                log("WifiDirect client connect error: ${e.message}")
            }
        }
    }

    private fun handleNewSocket(socket: Socket, isServer: Boolean) {
        try {
            val dos = DataOutputStream(socket.getOutputStream())
            val dis = DataInputStream(socket.getInputStream())
            val ourId = localIdentity?.userId ?: Build.MODEL ?: "unknown"
            val hello = """{"t":"hello","id":"$ourId"}""".encodeToByteArray()
            if (isServer) {
                val len = dis.readInt()
                val buf = ByteArray(len).apply { dis.readFully(this) }
                val peerId = parseHelloEndpointId(buf) ?: socket.remoteSocketAddress.toString()
                dos.writeInt(hello.size)
                dos.write(hello)
                dos.flush()
                registerPeer(peerId, socket)
                startReadLoop(peerId, dis)
            } else {
                dos.writeInt(hello.size)
                dos.write(hello)
                dos.flush()
                val len = dis.readInt()
                val buf = ByteArray(len).apply { dis.readFully(this) }
                val peerId = parseHelloEndpointId(buf) ?: socket.remoteSocketAddress.toString()
                registerPeer(peerId, socket)
                startReadLoop(peerId, dis)
            }
        } catch (e: Exception) {
            log("WifiDirect handshake error: ${e.message}")
            socket.close()
        }
    }

    private fun parseHelloEndpointId(bytes: ByteArray): String? {
        return runCatching {
            val json = org.json.JSONObject(bytes.decodeToString())
            if (json.optString("t") == "hello") json.optString("id").takeIf { it.isNotBlank() } else null
        }.getOrNull()
    }

    private fun registerPeer(endpointId: String, socket: Socket) {
        connected.add(endpointId)
        peerNamesByEndpoint[endpointId] = endpointId
        peerSocketByEndpoint[endpointId] = socket
        syncConnectedPeerInfos()
        log("WifiDirect peer connected: $endpointId")
    }

    private fun startReadLoop(endpointId: String, dis: DataInputStream) {
        scope.launch(Dispatchers.IO) {
            try {
                while (true) {
                    val len = dis.readInt()
                    if (len <= 0 || len > 32 * 1024) break
                    val buf = ByteArray(len)
                    dis.readFully(buf)
                    runCatching { MeshEnvelope.fromJsonBytes(buf) }.onSuccess { envelope ->
                        handleEnvelope(endpointId, envelope)
                    }
                }
            } catch (e: Exception) {
                log("WifiDirect read error from $endpointId: ${e.message}")
            }
            peerSocketByEndpoint.remove(endpointId)
            connected.remove(endpointId)
            peerNamesByEndpoint.remove(endpointId)
            syncConnectedPeerInfos()
        }
    }

    private val _connectedEndpoints = MutableStateFlow<List<String>>(emptyList())
    val connectedEndpoints: StateFlow<List<String>> = _connectedEndpoints.asStateFlow()

    private fun syncConnectedPeerInfos() {
        val list = connected.map { endpointId ->
            val raw = peerNamesByEndpoint[endpointId] ?: endpointId
            NearbyMeshClient.PeerInfo(endpointId, raw, "", raw)
        }
        _connectedPeerInfos.value = list
        _connectedEndpoints.value = connected.toList()
    }

    private fun handleEnvelope(fromEndpointId: String, envelope: MeshEnvelope) {
        if (!MeshSignature.verify(envelope)) return
        val trustOk = identityTrustStore.validateOrRemember(
            userId = envelope.senderUserId,
            orgId = envelope.senderOrgId,
            level = envelope.senderLevel,
            senderPublicKeyBase64 = envelope.senderPublicKeyBase64,
        )
        if (!trustOk) return
        when (envelope.type) {
            MeshMessageType.ACK -> { /* можно учитывать для метрик */ }
            MeshMessageType.CHAT -> {
                if (!seenMessageIds.add(envelope.id)) return
                val identity = localIdentity ?: return
                val recipient = envelope.recipientUserId?.substringBefore("|")?.trim()
                val iAmRecipient = recipient == null || identity.userId.substringBefore("|").trim() == recipient
                if (recipient != null && !iAmRecipient) return
                val canRead = recipient == null || iAmRecipient
                val plainText = if (canRead) {
                    val key = PolicyKeyService.resolveReadKey(identity, envelope.keyId)
                    key?.let { MeshCrypto.decryptWithKey(envelope.ivBase64, envelope.cipherTextBase64, it) }
                } else null
                val content = plainText?.let { ContentCodec.decode(it) }
                if (content is OutboundContent.CallSignal || content is OutboundContent.AudioPacket) {
                    _realtimeMessages.value = (_realtimeMessages.value + ReceivedMeshMessage(
                        fromEndpointId, envelope, plainText, canRead && plainText != null, content?.kind, ""
                    )).takeLast(500)
                    return
                }
                val summary = when (content) {
                    is OutboundContent.Text -> content.text.take(80)
                    else -> "[${content?.kind?.name ?: "?"}]"
                }
                val msg = ReceivedMeshMessage(
                    fromEndpointId, envelope, plainText, canRead && plainText != null,
                    content?.kind, summary
                )
                _incomingMessages.value = (_incomingMessages.value + msg).takeLast(500)
                if (canRead && plainText != null && content != null) {
                    val senderPeerId = envelope.senderUserId.substringBefore("|").trim()
                    chatHistoryStore.addMessage(senderPeerId, StoredChatMessage(
                        id = envelope.id,
                        text = summary,
                        timestampMs = envelope.timestampMs,
                        isOutgoing = false,
                        type = StoredMessageType.TEXT,
                        fileName = null,
                        filePath = null,
                        voiceFileId = null,
                    ))
                }
            }
            MeshMessageType.TOPOLOGY -> { /* опционально */ }
        }
    }

    private fun sendToTargets(targets: List<String>, envelope: MeshEnvelope): Int {
        if (targets.isEmpty()) return 0
        val bytes = envelope.toJsonBytes()
        if (bytes.size > 32 * 1024) return 0
        var sent = 0
        for (endpointId in targets) {
            val socket = peerSocketByEndpoint[endpointId] ?: continue
            try {
                val dos = DataOutputStream(socket.getOutputStream())
                dos.writeInt(bytes.size)
                dos.write(bytes)
                dos.flush()
                sent++
            } catch (e: Exception) {
                log("WifiDirect send error to $endpointId: ${e.message}")
            }
        }
        return sent
    }

    fun start(identity: LocalIdentity, displayName: String? = null) {
        if (manager == null || channel == null) {
            log("WifiDirect not available (no WifiP2pManager)")
            return
        }
        localIdentity = identity
        signer.useIdentity(identity.userId)
        discoveryRunning.set(true)
        _isRunning.value = true
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, filter)
        }
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = log("WifiDirect discoverPeers started (без Bluetooth)")
            override fun onFailure(reason: Int) = log("WifiDirect discoverPeers failed: $reason")
        })
        // Повторный поиск каждые 15 сек
        scope.launch {
            while (discoveryRunning.get()) {
                kotlinx.coroutines.delay(15000)
                if (!discoveryRunning.get()) break
                manager.discoverPeers(channel, null)
            }
        }
    }

    fun stop() {
        discoveryRunning.set(false)
        try {
            appContext.unregisterReceiver(receiver)
        } catch (_: Exception) {}
        peerSocketByEndpoint.values.forEach { it.close() }
        peerSocketByEndpoint.clear()
        serverSocket?.close()
        serverSocket = null
        connected.clear()
        peerNamesByEndpoint.clear()
        syncConnectedPeerInfos()
        _incomingMessages.value = emptyList()
        _realtimeMessages.value = emptyList()
        _isRunning.value = false
        log("WifiDirect stopped")
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

    fun sendChat(envelope: MeshEnvelope, previewText: String): Int =
        sendToTargets(connected.toList(), envelope)

    fun sendToPeer(peerUserId: String, sender: LocalIdentity, content: OutboundContent): Int {
        val normPeer = peerUserId.substringBefore("|").trim()
        val endpointId = peerNamesByEndpoint.entries.find { it.value == normPeer || it.value.startsWith("$normPeer|") }?.key
            ?: run {
                log("sendToPeer: no endpoint for $normPeer")
                return 0
            }
        val policy = AccessPolicy()
        val encoded = ContentCodec.encode(content)
        val envelope = buildChatEnvelope(sender, encoded, policy, ttl = 1, recipientUserId = normPeer) ?: return 0
        return sendToTargets(listOf(endpointId), envelope)
    }

    fun sendChatToPeer(peerUserId: String, sender: LocalIdentity, content: OutboundContent, policy: AccessPolicy, ttl: Int): Int {
        val normPeer = peerUserId.substringBefore("|").trim()
        val endpointId = peerNamesByEndpoint.entries.find { it.value == normPeer || it.value.startsWith("$normPeer|") }?.key
            ?: return 0
        val encoded = ContentCodec.encode(content)
        val envelope = buildChatEnvelope(sender, encoded, policy, ttl, recipientUserId = normPeer) ?: return 0
        return sendToTargets(listOf(endpointId), envelope)
    }

    fun rememberSentContent(content: OutboundContent) = Unit

    fun broadcast(sender: LocalIdentity, content: OutboundContent) {
        val policy = AccessPolicy()
        val encoded = ContentCodec.encode(content)
        val envelope = buildChatEnvelope(sender, encoded, policy, ttl = 2) ?: return
        sendChat(envelope, encoded.take(50).toString())
    }

    fun transportHealth(): TransportHealth {
        val peers = connected.size
        val available = _isRunning.value && peers > 0
        return TransportHealth(
            type = TransportType.WIFI_DIRECT,
            available = available,
            estimatedLatencyMs = if (peers > 0) 60 else 999,
            estimatedBandwidthKbps = if (peers > 0) 8000 else 0,
            estimatedBatteryCost = 5,
            stabilityScore = if (peers > 0) 7 else 2,
        )
    }

    // Совместимость с API NearbyMeshClient — заглушки
    val topology = MutableStateFlow(com.peerdone.app.data.TopologySnapshot())
    val deliveryMetrics = MutableStateFlow(com.peerdone.app.data.DeliveryMetrics())
    val stats = MutableStateFlow(com.peerdone.app.data.MeshStats())
    val testConfig = MutableStateFlow(com.peerdone.app.data.NetworkTestConfig())
    val outboundRecords = MutableStateFlow<List<OutboundMessageRecord>>(emptyList())
    fun setDropAcksForDemo(enabled: Boolean) = Unit
    fun setForwardingEnabled(enabled: Boolean) = Unit
    fun setPacketLossSimulation(inboundPercent: Int, outboundPercent: Int) = Unit
    fun resetDeliveryMetrics() = Unit
    fun requestHistoryFromPeer(peerId: String, sender: LocalIdentity): Int = 0
}
