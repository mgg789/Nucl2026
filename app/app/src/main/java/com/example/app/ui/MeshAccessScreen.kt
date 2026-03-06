package com.example.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.app.core.message.ContentKind
import com.example.app.core.message.OutboundContent
import com.example.app.core.transport.DeliveryClass
import com.example.app.core.transport.StubTransportAdapter
import com.example.app.core.transport.TransportAdapter
import com.example.app.core.transport.TransportHealth
import com.example.app.core.transport.TransportRegistry
import com.example.app.core.transport.TransportStrategy
import com.example.app.core.transport.TransportType
import com.example.app.data.DeviceIdentityStore
import com.example.app.data.NearbyMeshClient
import com.example.app.data.NetworkTestConfig
import com.example.app.data.NearbyTransportAdapter
import com.example.app.data.TopologySnapshot
import com.example.app.domain.AccessPolicy
import com.example.app.domain.LocalIdentity
import com.example.app.service.OutboundQueuedMessage
import com.example.app.service.SendOrchestrator
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class AppTab {
    CHATS,
    CALLS,
    PEERS,
    DEV,
}

private data class ChatPreview(
    val peerUserId: String,
    val lastMessage: String,
    val online: Boolean,
    val unreadCount: Int,
    val lastTime: String,
)

private data class UiChatMessage(
    val peerUserId: String,
    val text: String,
    val timestampMs: Long,
    val outgoing: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshAccessScreen(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val nearbyClient = remember { NearbyMeshClient(context) }
    val identityStore = remember { DeviceIdentityStore(context) }

    val connectedPeers by nearbyClient.connectedEndpoints.collectAsState()
    val networkLogs by nearbyClient.logs.collectAsState()
    val incoming by nearbyClient.incomingMessages.collectAsState()
    val isRunning by nearbyClient.isRunning.collectAsState()
    val stats by nearbyClient.stats.collectAsState()
    val deliveryMetrics by nearbyClient.deliveryMetrics.collectAsState()
    val testConfig by nearbyClient.testConfig.collectAsState()
    val outboundRecords by nearbyClient.outboundRecords.collectAsState()
    val topology by nearbyClient.topology.collectAsState()

    var localIdentity by remember { mutableStateOf(identityStore.getOrCreate()) }
    var selectedTab by remember { mutableStateOf(AppTab.CHATS) }
    var selectedChatPeerId by remember { mutableStateOf<String?>(null) }
    var messageDraft by remember { mutableStateOf("") }
    val sendLog = remember { mutableStateListOf<String>() }
    val localEchoMessages = remember { mutableStateListOf<UiChatMessage>() }

    val adapters = remember(nearbyClient) {
        listOf<TransportAdapter>(
            NearbyTransportAdapter(nearbyClient),
            StubTransportAdapter(
                type = TransportType.BLUETOOTH_LE,
                staticHealth = TransportHealth(TransportType.BLUETOOTH_LE, false, 250, 800, 7, 4),
            ),
            StubTransportAdapter(
                type = TransportType.WIFI_DIRECT,
                staticHealth = TransportHealth(TransportType.WIFI_DIRECT, false, 110, 10000, 7, 5),
            ),
            StubTransportAdapter(
                type = TransportType.LAN_P2P,
                staticHealth = TransportHealth(TransportType.LAN_P2P, false, 30, 30000, 3, 8),
            ),
            StubTransportAdapter(
                type = TransportType.INTERNET_RELAY,
                staticHealth = TransportHealth(TransportType.INTERNET_RELAY, false, 90, 20000, 3, 9),
            ),
        )
    }
    val adaptersByType = remember(adapters) { adapters.associateBy { it.type } }
    val transportRegistry = remember(adapters) {
        TransportRegistry().apply {
            adapters.forEach { adapter -> register { adapter.health() } }
        }
    }
    val sendOrchestrator = remember(adaptersByType, transportRegistry) {
        SendOrchestrator(adaptersByType, transportRegistry)
    }
    val pendingQueue by sendOrchestrator.pendingMessages.collectAsState()
    val transportDecision = remember(isRunning, connectedPeers, stats) {
        TransportStrategy.chooseBest(DeliveryClass.INTERACTIVE, transportRegistry.snapshot())
    }

    val requestPermissions = rememberRuntimePermissions()
    LaunchedEffect(Unit) {
        requestPermissions()
    }
    LaunchedEffect(localIdentity.userId) {
        if (!isRunning) {
            nearbyClient.start(localIdentity)
        }
    }
    DisposableEffect(Unit) {
        onDispose { nearbyClient.stop() }
    }
    LaunchedEffect(isRunning, connectedPeers.size) {
        if (isRunning && connectedPeers.isNotEmpty()) {
            sendOrchestrator.flushAll()
        }
    }
    LaunchedEffect(isRunning) {
        while (nearbyClient.isRunning.value) {
            delay(1200)
            sendOrchestrator.flushAll()
        }
    }

    val onlineNames = remember(topology) { topology.nodes.toSet() }
    val discoveredPeers = remember(incoming, topology, localIdentity) {
        (incoming.map { it.envelope.senderUserId } + topology.nodes)
            .filter { it.isNotBlank() && it != localIdentity.userId }
            .distinct()
            .sorted()
    }
    val chatPreviews = remember(incoming, discoveredPeers, onlineNames, localEchoMessages) {
        discoveredPeers.map { peerId ->
                val peerMessages = incoming.filter { it.envelope.senderUserId == peerId }
                val peerLocal = localEchoMessages.filter { it.peerUserId == peerId }
                val lastIncomingTs = peerMessages.lastOrNull()?.envelope?.timestampMs ?: 0L
                val lastLocalTs = peerLocal.lastOrNull()?.timestampMs ?: 0L
                val useLocal = lastLocalTs > lastIncomingTs
                val last = if (useLocal) {
                    peerLocal.lastOrNull()?.text
                } else {
                    peerMessages.lastOrNull()?.contentSummary
                }
                    ?: "Нет сообщений"
                val lastTs = if (useLocal) lastLocalTs else lastIncomingTs
                val unread = peerMessages.takeLast(8).count { it.accessGranted && it.contentSummary != "hidden" }
                ChatPreview(
                    peerUserId = peerId,
                    lastMessage = last,
                    online = peerId in onlineNames,
                    unreadCount = unread,
                    lastTime = lastTs?.toChatTime() ?: "--:--",
                )
            }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                NavigationBarItem(
                    selected = selectedTab == AppTab.CHATS,
                    onClick = { selectedTab = AppTab.CHATS },
                    icon = { Text("💬") },
                    label = { Text("Чаты") },
                )
                NavigationBarItem(
                    selected = selectedTab == AppTab.CALLS,
                    onClick = { selectedTab = AppTab.CALLS },
                    icon = { Text("📞") },
                    label = { Text("Звонки") },
                )
                NavigationBarItem(
                    selected = selectedTab == AppTab.PEERS,
                    onClick = { selectedTab = AppTab.PEERS },
                    icon = { Text("🌐") },
                    label = { Text("Сеть") },
                )
                NavigationBarItem(
                    selected = selectedTab == AppTab.DEV,
                    onClick = { selectedTab = AppTab.DEV },
                    icon = { Text("⚙") },
                    label = { Text("Dev") },
                )
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            when (selectedTab) {
                AppTab.CHATS -> {
                    if (selectedChatPeerId == null) {
                        ChatsScreen(
                            localIdentity = localIdentity,
                            chatPreviews = chatPreviews,
                            onOpenChat = { selectedChatPeerId = it },
                        )
                    } else {
                        ChatDialogScreen(
                            peerUserId = selectedChatPeerId!!,
                            incoming = incoming,
                            localEchoMessages = localEchoMessages,
                            onBack = { selectedChatPeerId = null },
                            draft = messageDraft,
                            onDraftChange = { messageDraft = it },
                            onSendDemoFile = {
                                val policy = AccessPolicy()
                                val bytes = "Mesh demo file from ${localIdentity.userId} at ${System.currentTimeMillis()}".toByteArray()
                                val outcomes = sendOrchestrator.enqueueFileTransfer(
                                    sender = localIdentity,
                                    fileName = "mesh-demo-${localIdentity.userId}.txt",
                                    mimeType = "text/plain",
                                    bytes = bytes,
                                    policy = policy,
                                )
                                sendLog.add("file -> outcomes=${outcomes.count { it.sent }}/${outcomes.size}")
                            },
                            onSend = {
                                val policy = AccessPolicy()
                                val outcome = sendOrchestrator.enqueueAndTrySend(
                                    sender = localIdentity,
                                    content = OutboundContent.Text(messageDraft),
                                    policy = policy,
                                    deliveryClass = DeliveryClass.INTERACTIVE,
                                )
                                sendLog.add("send -> ${outcome.reason}")
                                localEchoMessages += UiChatMessage(
                                    peerUserId = selectedChatPeerId!!,
                                    text = messageDraft,
                                    timestampMs = System.currentTimeMillis(),
                                    outgoing = true,
                                )
                                messageDraft = ""
                            },
                        )
                    }
                }

                AppTab.CALLS -> {
                    CallsScreen(
                        incoming = incoming,
                        onSendOffer = {
                            val policy = AccessPolicy()
                            val outcome = sendOrchestrator.enqueueAndTrySend(
                                sender = localIdentity,
                                content = OutboundContent.CallSignal(
                                    callId = "call-${System.currentTimeMillis()}",
                                    phase = "offer",
                                    sdpOrIce = "v=0 demo-offer",
                                ),
                                policy = policy,
                                deliveryClass = DeliveryClass.REALTIME,
                            )
                            sendLog.add("call-offer -> ${outcome.reason}")
                        },
                    )
                }

                AppTab.PEERS -> {
                    PeersScreen(
                        isRunning = isRunning,
                        topology = topology,
                        transportDecision = transportDecision.selected?.name ?: "NONE",
                        onStart = { nearbyClient.start(localIdentity) },
                        onStop = { nearbyClient.stop() },
                    )
                }

                AppTab.DEV -> {
                    DevScreen(
                        stats = stats,
                        deliveryMetrics = deliveryMetrics,
                        testConfig = testConfig,
                        pendingQueueSize = pendingQueue.size,
                        outboundCount = outboundRecords.size,
                        logs = networkLogs.takeLast(30),
                        sendLog = sendLog.takeLast(20),
                        pendingFiles = buildFileQueueProgress(pendingQueue),
                        onEnableRelay = { nearbyClient.setForwardingEnabled(true) },
                        onDisableRelay = { nearbyClient.setForwardingEnabled(false) },
                        onLossLow = { nearbyClient.setPacketLossSimulation(inboundPercent = 10, outboundPercent = 10) },
                        onLossHigh = { nearbyClient.setPacketLossSimulation(inboundPercent = 30, outboundPercent = 30) },
                        onLossOff = { nearbyClient.setPacketLossSimulation(inboundPercent = 0, outboundPercent = 0) },
                        onResetMetrics = { nearbyClient.resetDeliveryMetrics() },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatsScreen(
    localIdentity: LocalIdentity,
    chatPreviews: List<ChatPreview>,
    onOpenChat: (String) -> Unit,
) {
    val activeChip = Color(0xFF8F82DB)
    val idleChipText = Color(0xFF838383)
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Alastra", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(
                "${localIdentity.userId} · в сети",
                color = Color(0xFF16C17B),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Surface(shape = RoundedCornerShape(100.dp), color = Color.Transparent, tonalElevation = 0.dp, modifier = Modifier.border(1.dp, idleChipText, RoundedCornerShape(100.dp))) {
                    Text("Группы", modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp), color = idleChipText)
                }
                Surface(shape = RoundedCornerShape(100.dp), color = activeChip) {
                    Text("Чаты", modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp), color = Color.White)
                }
                Surface(shape = RoundedCornerShape(100.dp), color = Color.Transparent, tonalElevation = 0.dp, modifier = Modifier.border(1.dp, idleChipText, RoundedCornerShape(100.dp))) {
                    Text("Сервисы", modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp), color = idleChipText)
                }
            }
        }
        items(chatPreviews) { chat ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onOpenChat(chat.peerUserId) }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Text(chat.peerUserId.take(1).uppercase()) }
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(chat.peerUserId, fontWeight = FontWeight.Normal, style = MaterialTheme.typography.titleMedium)
                        Text(chat.lastTime, style = MaterialTheme.typography.labelMedium, color = Color(0xFF838383))
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(chat.lastMessage, maxLines = 1, color = Color(0xFF565555), style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (chat.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                    )
                    if (chat.unreadCount > 0) {
                        Badge(containerColor = Color(0xFF836EF7)) { Text("${chat.unreadCount}") }
                    }
                }
            }
            HorizontalDivider(color = Color(0x33FFFFFF))
        }
    }
}

@Composable
private fun ChatDialogScreen(
    peerUserId: String,
    incoming: List<com.example.app.data.ReceivedMeshMessage>,
    localEchoMessages: List<UiChatMessage>,
    onBack: () -> Unit,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSendDemoFile: () -> Unit,
    onSend: () -> Unit,
) {
    val remoteMessages = incoming
        .filter { it.envelope.senderUserId == peerUserId }
        .map {
            UiChatMessage(
                peerUserId = peerUserId,
                text = it.contentSummary,
                timestampMs = it.envelope.timestampMs,
                outgoing = false,
            )
        }
    val ownMessages = localEchoMessages.filter { it.peerUserId == peerUserId }
    val allMessages = (remoteMessages + ownMessages).sortedBy { it.timestampMs }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp)
            .imePadding(),
    ) {
        Spacer(Modifier.height(10.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFDDDDDD), RoundedCornerShape(16.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            TextButton(onClick = onBack) { Text("←") }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFF3F3F3)),
                contentAlignment = Alignment.Center,
            ) {
                Text(peerUserId.take(1).uppercase(), color = Color(0xFF121212))
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(peerUserId, style = MaterialTheme.typography.titleMedium, color = Color(0xFF121212))
                Text("В сети", color = Color(0xFF8F82DB), style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.weight(1f))
            Text("📞", color = Color(0xFF121212))
            Spacer(Modifier.width(12.dp))
            Text("⋮", color = Color(0xFF121212))
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            reverseLayout = true,
        ) {
            items(allMessages.takeLast(80).reversed()) { m ->
                val incomingBubble = !m.outgoing
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (incomingBubble) Arrangement.Start else Arrangement.End,
                ) {
                    Card(
                        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 8.dp, bottomEnd = 20.dp, bottomStart = 20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (incomingBubble) {
                                Color(0xFFE5E5E5)
                            } else {
                                Color(0xFFCCC7E9)
                            },
                        ),
                        modifier = Modifier.fillMaxWidth(0.82f),
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(m.text, color = Color(0xFF2D2D2D))
                            Spacer(Modifier.height(2.dp))
                            Text(
                                m.timestampMs.toChatTime(),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF555555),
                            )
                        }
                    }
                }
            }
        }
        Text("6 марта", color = Color(0xFF555555), style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(bottom = 10.dp)
                .background(Color(0x4DA6A6A6), RoundedCornerShape(100.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Button(
                onClick = onSendDemoFile,
                shape = CircleShape,
                modifier = Modifier.size(42.dp),
                contentPadding = PaddingValues(0.dp),
            ) { Text("+") }
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(100.dp),
                placeholder = { Text("Отправьте сообщение") },
            )
            Spacer(Modifier.size(8.dp))
            Button(
                onClick = onSend,
                enabled = draft.isNotBlank(),
                shape = CircleShape,
                modifier = Modifier.size(44.dp),
                contentPadding = PaddingValues(0.dp),
            ) { Text("➤", color = Color.White) }
        }
    }
}

@Composable
private fun CallsScreen(
    incoming: List<com.example.app.data.ReceivedMeshMessage>,
    onSendOffer: () -> Unit,
) {
    val callSignals = incoming.filter { it.contentKind == ContentKind.CALL_SIGNAL }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("NodeX", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("Сегодня, ${SimpleDateFormat("d MMMM", Locale.forLanguageTag("ru")).format(Date())}", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
            items(callSignals.takeLast(30)) { s ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF121212))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.size(42.dp).clip(CircleShape).background(Color(0xFFF3F3F3)),
                        contentAlignment = Alignment.Center,
                    ) { Text(s.envelope.senderUserId.take(1), color = Color(0xFF121212)) }
                    Column(Modifier.padding(start = 10.dp).weight(1f)) {
                        Text(s.envelope.senderUserId, color = Color(0xFF555555), style = MaterialTheme.typography.titleSmall)
                        Text("phase: ${s.contentSummary}", color = Color(0xFF838383), style = MaterialTheme.typography.bodySmall)
                    }
                    Text("📞", color = Color(0xFF16C17B))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onSendOffer,
                shape = RoundedCornerShape(21.dp),
                modifier = Modifier.weight(1f),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFFFFC4C4)),
            ) {
                Text("Старт звонка", color = Color(0xFFFF7E7E), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PeersScreen(
    isRunning: Boolean,
    topology: TopologySnapshot,
    transportDecision: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("PeerMap", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Сеть: ${if (isRunning) "ON" else "OFF"}; auto=$transportDecision")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart) { Text("Start") }
            Button(onClick = onStop) { Text("Stop") }
        }
        Spacer(Modifier.height(10.dp))
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Nodes: ${topology.nodes.size}, edges: ${topology.edges.size}",
                modifier = Modifier.padding(10.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(topology.edges.take(100)) { edge ->
                Text("• ${edge.fromNode} -> ${edge.toNode}")
            }
        }
    }
}

@Composable
private fun DevScreen(
    stats: com.example.app.data.MeshStats,
    deliveryMetrics: com.example.app.data.DeliveryMetrics,
    testConfig: NetworkTestConfig,
    pendingQueueSize: Int,
    outboundCount: Int,
    logs: List<String>,
    sendLog: List<String>,
    pendingFiles: List<String>,
    onEnableRelay: () -> Unit,
    onDisableRelay: () -> Unit,
    onLossLow: () -> Unit,
    onLossHigh: () -> Unit,
    onLossOff: () -> Unit,
    onResetMetrics: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text("Dev", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item { Text("seen=${stats.seenCount}, dup=${stats.droppedDuplicates}, fwd=${stats.forwardedCount}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Text("pendingAck=${stats.pendingAckCount}, ackOk=${stats.ackReceivedCount}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Text("sent=${deliveryMetrics.sentCount}, acked=${deliveryMetrics.ackedCount}, lost=${deliveryMetrics.lostCount}, loss=${deliveryMetrics.lossPercent}%", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Text("ackRtt avg=${deliveryMetrics.avgAckRttMs}ms p95=${deliveryMetrics.p95AckRttMs}ms", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Text("relay=${if (testConfig.forwardingEnabled) "ON" else "OFF"}, loss in=${testConfig.inboundDropPercent}% out=${testConfig.outboundDropPercent}%", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Text("sendQueue=$pendingQueueSize, outboundRecords=$outboundCount", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onEnableRelay) { Text("Relay ON") }
                Button(onClick = onDisableRelay) { Text("Relay OFF") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onLossOff) { Text("Loss 0%") }
                Button(onClick = onLossLow) { Text("Loss 10%") }
                Button(onClick = onLossHigh) { Text("Loss 30%") }
            }
        }
        item {
            Button(onClick = onResetMetrics) { Text("Reset metrics") }
        }
        item { HorizontalDivider() }
        item { Text("File queue") }
        if (pendingFiles.isEmpty()) {
            item { Text("Нет активных файлов", style = MaterialTheme.typography.bodySmall) }
        } else {
            items(pendingFiles) { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
        }
        item { HorizontalDivider() }
        item { Text("Send log") }
        items(sendLog) { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
        item { HorizontalDivider() }
        item { Text("Network log") }
        items(logs) { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun Long.toChatTime(): String {
    val format = SimpleDateFormat("HH:mm", Locale.getDefault())
    return format.format(Date(this))
}

private fun buildFileQueueProgress(pending: List<OutboundQueuedMessage>): List<String> {
    val grouped = pending.mapNotNull { queued ->
        val content = queued.content
        when (content) {
            is OutboundContent.FileMeta -> content.fileId to Triple(content.fileName, 0, 0)
            is OutboundContent.FileChunk -> content.fileId to Triple("file-${content.fileId.take(6)}", content.chunkIndex + 1, content.chunkTotal)
            else -> null
        }
    }.groupBy({ it.first }, { it.second })

    return grouped.values.map { parts ->
        val fileName = parts.firstOrNull { it.first.isNotBlank() }?.first ?: "file"
        val progress = parts.maxOfOrNull { it.second } ?: 0
        val total = parts.maxOfOrNull { it.third } ?: 0
        if (total > 0) "$fileName: $progress/$total chunks в очереди" else "$fileName: preparing..."
    }
}

@Composable
private fun rememberRuntimePermissions(): () -> Unit {
    val context = LocalContext.current
    val permissions = remember {
        buildList {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
    }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = {},
    )

    return remember(permissions, launcher, context) {
        {
            val need = permissions.filter {
                ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (need.isNotEmpty()) {
                launcher.launch(need.toTypedArray())
            }
        }
    }
}

