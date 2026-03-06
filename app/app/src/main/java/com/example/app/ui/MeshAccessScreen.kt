package com.example.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.app.core.message.OutboundContent
import com.example.app.core.transport.DeliveryClass
import com.example.app.core.transport.StubTransportAdapter
import com.example.app.core.transport.TransportAdapter
import com.example.app.core.transport.TransportRegistry
import com.example.app.core.transport.TransportStrategy
import com.example.app.core.transport.TransportType
import com.example.app.data.NearbyMeshClient
import com.example.app.data.NearbyTransportAdapter
import com.example.app.data.TopologySnapshot
import com.example.app.domain.AccessPolicy
import com.example.app.domain.LocalIdentity
import com.example.app.domain.PolicyEngine
import com.example.app.domain.SampleDirectory
import com.example.app.service.SendOrchestrator
import kotlin.math.roundToInt

@Composable
fun MeshAccessScreen(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val nearbyClient = remember { NearbyMeshClient(context) }
    val connectedPeers by nearbyClient.connectedEndpoints.collectAsState()
    val networkLogs by nearbyClient.logs.collectAsState()
    val incoming by nearbyClient.incomingMessages.collectAsState()
    val isRunning by nearbyClient.isRunning.collectAsState()
    val stats by nearbyClient.stats.collectAsState()
    val outboundRecords by nearbyClient.outboundRecords.collectAsState()
    val topology by nearbyClient.topology.collectAsState()

    val directory = remember { SampleDirectory.users }
    val identities = remember { SampleDirectory.identities }
    val allOrgs = remember(directory) { directory.map { it.orgId }.toSet().toList().sorted() }

    var localIdentity by remember { mutableStateOf(identities.first()) }
    var messageText by remember { mutableStateOf("Тестовое сообщение офлайн-сети") }
    var maxLevelEnabled by remember { mutableStateOf(true) }
    var maxLevel by remember { mutableStateOf(2f) }
    var minLevelEnabled by remember { mutableStateOf(false) }
    var minLevel by remember { mutableStateOf(0f) }

    val includeOrgs = remember { mutableStateListOf<String>() }
    val excludeOrgs = remember { mutableStateListOf<String>() }
    val sendLog = remember { mutableStateListOf<String>() }
    var demoDropAcks by remember { mutableStateOf(false) }
    val adapters = remember(nearbyClient) {
        val list: List<TransportAdapter> = listOf(
            NearbyTransportAdapter(nearbyClient),
            StubTransportAdapter(
                type = TransportType.BLUETOOTH_LE,
                staticHealth = com.example.app.core.transport.TransportHealth(
                    type = TransportType.BLUETOOTH_LE,
                    available = false,
                    estimatedLatencyMs = 250,
                    estimatedBandwidthKbps = 800,
                    estimatedBatteryCost = 7,
                    stabilityScore = 4,
                ),
            ),
            StubTransportAdapter(
                type = TransportType.WIFI_DIRECT,
                staticHealth = com.example.app.core.transport.TransportHealth(
                    type = TransportType.WIFI_DIRECT,
                    available = false,
                    estimatedLatencyMs = 110,
                    estimatedBandwidthKbps = 10000,
                    estimatedBatteryCost = 7,
                    stabilityScore = 5,
                ),
            ),
            StubTransportAdapter(
                type = TransportType.LAN_P2P,
                staticHealth = com.example.app.core.transport.TransportHealth(
                    type = TransportType.LAN_P2P,
                    available = false,
                    estimatedLatencyMs = 30,
                    estimatedBandwidthKbps = 30000,
                    estimatedBatteryCost = 3,
                    stabilityScore = 8,
                ),
            ),
            StubTransportAdapter(
                type = TransportType.INTERNET_RELAY,
                staticHealth = com.example.app.core.transport.TransportHealth(
                    type = TransportType.INTERNET_RELAY,
                    available = false,
                    estimatedLatencyMs = 90,
                    estimatedBandwidthKbps = 20000,
                    estimatedBatteryCost = 3,
                    stabilityScore = 9,
                ),
            ),
        )
        list
    }
    val adaptersByType = remember(adapters) { adapters.associateBy { it.type } }
    val transportRegistry = remember(adapters) {
        TransportRegistry().apply {
            adapters.forEach { adapter ->
                register { adapter.health() }
            }
        }
    }
    val sendOrchestrator = remember(adaptersByType, transportRegistry) { SendOrchestrator(adaptersByType, transportRegistry) }
    val pendingQueue by sendOrchestrator.pendingMessages.collectAsState()
    var selectedDeliveryClass by remember { mutableStateOf(DeliveryClass.INTERACTIVE) }
    var selectedPayloadType by remember { mutableStateOf("TEXT") }

    val requestPermissions = rememberRuntimePermissions()
    LaunchedEffect(Unit) {
        requestPermissions()
    }

    DisposableEffect(Unit) {
        onDispose {
            nearbyClient.stop()
        }
    }
    LaunchedEffect(isRunning, connectedPeers.size) {
        if (isRunning && connectedPeers.isNotEmpty()) {
            sendOrchestrator.flushAll()
        }
    }

    val policy = AccessPolicy(
        maxLevel = if (maxLevelEnabled) maxLevel.roundToInt() else null,
        minLevel = if (minLevelEnabled) minLevel.roundToInt() else null,
        includeOrgs = includeOrgs.toSet(),
        excludeOrgs = excludeOrgs.toSet(),
    )

    val simulatedRecipients = remember(policy, directory.toList(), localIdentity) {
        PolicyEngine.resolveRecipients(policy, directory)
            .filter { it.userId != localIdentity.userId }
    }

    val readableIncoming = incoming.map { msg -> msg to msg.accessGranted }
    val transportDecision = remember(isRunning, connectedPeers, stats) {
        TransportStrategy.chooseBest(
            deliveryClass = DeliveryClass.INTERACTIVE,
            healthList = transportRegistry.snapshot(),
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item {
            Text(
                text = "Offline Mesh + Access Policy",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "Google Nearby + фильтры уровней/организаций",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            LocalIdentityCard(
                identities = identities,
                selected = localIdentity,
                onSelect = { localIdentity = it },
            )
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Сеть", style = MaterialTheme.typography.titleMedium)
                    Text("Состояние: ${if (isRunning) "запущено" else "остановлено"}")
                    Text("Подключенные пиры: ${connectedPeers.size}")
                    Text("Seen: ${stats.seenCount}, dupDrop: ${stats.droppedDuplicates}, forwarded: ${stats.forwardedCount}")
                    Text("Pending ACK: ${stats.pendingAckCount}, ACK ok: ${stats.ackReceivedCount}")
                    Text("Queued for send: ${pendingQueue.size}")
                    Text("Auto transport: ${transportDecision.selected?.name ?: "NONE"}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { nearbyClient.start(localIdentity) }) {
                            Text("Старт Nearby")
                        }
                        Button(onClick = { nearbyClient.stop() }) {
                            Text("Стоп")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                demoDropAcks = !demoDropAcks
                                nearbyClient.setDropAcksForDemo(demoDropAcks)
                            }
                        ) {
                            Text(if (demoDropAcks) "ACK drop: ON" else "ACK drop: OFF")
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Transport ranking", style = MaterialTheme.typography.titleMedium)
                    transportDecision.ranked.forEach { tr ->
                        Text(
                            "• ${tr.type}: avail=${tr.available}, lat=${tr.estimatedLatencyMs}ms, bw=${tr.estimatedBandwidthKbps}kbps, bat=${tr.estimatedBatteryCost}, st=${tr.stabilityScore}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        "reason: ${transportDecision.reason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            TopologyCard(
                localIdentity = localIdentity,
                topology = topology,
            )
        }

        item {
            OutlinedTextField(
                value = messageText,
                onValueChange = { messageText = it },
                label = { Text("Текст сообщения") },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item {
            FilterLevelsCard(
                maxLevelEnabled = maxLevelEnabled,
                onMaxLevelEnabledChange = { maxLevelEnabled = it },
                maxLevel = maxLevel,
                onMaxLevelChange = { maxLevel = it },
                minLevelEnabled = minLevelEnabled,
                onMinLevelEnabledChange = { minLevelEnabled = it },
                minLevel = minLevel,
                onMinLevelChange = { minLevel = it },
            )
        }

        item {
            OrgsFilterCard(
                title = "Организации, которые получат (include)",
                orgs = allOrgs,
                selected = includeOrgs.toSet(),
                onToggle = { orgId ->
                    if (orgId in includeOrgs) includeOrgs.remove(orgId) else includeOrgs.add(orgId)
                },
            )
        }

        item {
            OrgsFilterCard(
                title = "Организации, которые НЕ получат (exclude)",
                orgs = allOrgs,
                selected = excludeOrgs.toSet(),
                onToggle = { orgId ->
                    if (orgId in excludeOrgs) excludeOrgs.remove(orgId) else excludeOrgs.add(orgId)
                },
            )
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Итоговая политика",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text("maxLevel: ${policy.maxLevel ?: "none"}")
                    Text("minLevel: ${policy.minLevel ?: "none"}")
                    Text("includeOrgs: ${policy.includeOrgs.joinToString().ifBlank { "all" }}")
                    Text("excludeOrgs: ${policy.excludeOrgs.joinToString().ifBlank { "none" }}")
                    Text("По каталогу получат: ${simulatedRecipients.size}")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DeliveryClass.entries.forEach { dc ->
                            AssistChip(
                                onClick = { selectedDeliveryClass = dc },
                                label = {
                                    val selected = if (dc == selectedDeliveryClass) "✓ " else ""
                                    Text("$selected$dc")
                                },
                            )
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf("TEXT", "FILE_META", "CALL_SIGNAL").forEach { pt ->
                            AssistChip(
                                onClick = { selectedPayloadType = pt },
                                label = {
                                    val selected = if (pt == selectedPayloadType) "✓ " else ""
                                    Text("$selected$pt")
                                },
                            )
                        }
                    }
                    Button(
                        onClick = {
                            val payload = when (selectedPayloadType) {
                                "FILE_META" -> OutboundContent.FileMeta(
                                    fileName = "demo-${System.currentTimeMillis()}.bin",
                                    totalBytes = 1024L * 256L,
                                    mimeType = "application/octet-stream",
                                    sha256 = "demo-sha256",
                                )
                                "CALL_SIGNAL" -> OutboundContent.CallSignal(
                                    callId = "call-${System.currentTimeMillis()}",
                                    phase = "offer",
                                    sdpOrIce = "v=0 ... demo",
                                )
                                else -> OutboundContent.Text(messageText)
                            }
                            val outcome = sendOrchestrator.enqueueAndTrySend(
                                sender = localIdentity,
                                content = payload,
                                policy = policy,
                                deliveryClass = selectedDeliveryClass,
                            )
                            sendLog.add("Dispatch: sent=${outcome.sent}, transport=${outcome.transport ?: "NONE"}; ${outcome.reason}")
                        },
                    ) {
                        Text("Отправить (auto transport)")
                    }
                }
            }
        }

        item {
            Text(
                text = "Симуляция получателей (${simulatedRecipients.size})",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        items(simulatedRecipients) { user ->
            Card {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("${user.userId} (${user.orgId})")
                    Text(
                        "level=${user.level}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (readableIncoming.isNotEmpty()) {
            item {
                HorizontalDivider()
                Text("Входящие", style = MaterialTheme.typography.titleMedium)
            }
            items(readableIncoming) { (msg, canRead) ->
                Card {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("От: ${msg.envelope.senderUserId} (${msg.envelope.senderOrgId})")
                        Text(
                            if (canRead) "Payload: ${msg.contentSummary}" else "Payload скрыт: нет прав по политике",
                            color = if (canRead) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                        )
                        Text(
                            "policy max=${msg.envelope.policy.maxLevel ?: "-"} min=${msg.envelope.policy.minLevel ?: "-"}, ttl=${msg.envelope.ttl}, hop=${msg.envelope.hopCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "kind=${msg.contentKind ?: "unknown"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (sendLog.isNotEmpty()) {
            item {
                HorizontalDivider()
                Text(
                    text = "Локальный лог отправок",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(sendLog) { line ->
                Text(line, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (outboundRecords.isNotEmpty()) {
            item {
                HorizontalDivider()
                Text("Статусы доставки", style = MaterialTheme.typography.titleMedium)
            }
            items(outboundRecords.take(20)) { rec ->
                Card {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("id: ${rec.id.take(8)} | ${rec.state}")
                        Text("retries=${rec.retries}")
                        Text("msg: ${rec.preview}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (pendingQueue.isNotEmpty()) {
            item {
                HorizontalDivider()
                Text("Очередь отправки (orchestrator)", style = MaterialTheme.typography.titleMedium)
            }
            items(pendingQueue.take(20)) { q ->
                Card {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("qid: ${q.queueId.take(8)} | ${q.deliveryClass}")
                        Text("payload: ${q.content.kind}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (networkLogs.isNotEmpty()) {
            item {
                HorizontalDivider()
                Text("Логи Nearby", style = MaterialTheme.typography.titleMedium)
            }
            items(networkLogs.takeLast(20)) { line ->
                Text(line, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun LocalIdentityCard(
    identities: List<LocalIdentity>,
    selected: LocalIdentity,
    onSelect: (LocalIdentity) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Профиль устройства", style = MaterialTheme.typography.titleMedium)
            Text("Текущий: ${selected.userId}, org=${selected.orgId}, level=${selected.level}")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                identities.forEach { identity ->
                    AssistChip(
                        onClick = { onSelect(identity) },
                        label = {
                            val mark = if (identity == selected) "✓ " else ""
                            Text("$mark${identity.userId}")
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterLevelsCard(
    maxLevelEnabled: Boolean,
    onMaxLevelEnabledChange: (Boolean) -> Unit,
    maxLevel: Float,
    onMaxLevelChange: (Float) -> Unit,
    minLevelEnabled: Boolean,
    onMinLevelEnabledChange: (Boolean) -> Unit,
    minLevel: Float,
    onMinLevelChange: (Float) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Фильтр по уровню", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = maxLevelEnabled, onCheckedChange = onMaxLevelEnabledChange)
                Text("Разрешить уровни <= ${maxLevel.roundToInt()}")
            }
            if (maxLevelEnabled) {
                Slider(
                    value = maxLevel,
                    onValueChange = onMaxLevelChange,
                    valueRange = 0f..10f,
                    steps = 9,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = minLevelEnabled, onCheckedChange = onMinLevelEnabledChange)
                Text("Разрешить уровни >= ${minLevel.roundToInt()}")
            }
            if (minLevelEnabled) {
                Slider(
                    value = minLevel,
                    onValueChange = onMinLevelChange,
                    valueRange = 0f..10f,
                    steps = 9,
                )
            }
        }
    }
}

@Composable
private fun OrgsFilterCard(
    title: String,
    orgs: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                orgs.forEach { org ->
                    AssistChip(
                        onClick = { onToggle(org) },
                        label = {
                            val mark = if (org in selected) "✓ " else ""
                            Text("$mark$org")
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TopologyCard(
    localIdentity: LocalIdentity,
    topology: TopologySnapshot,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Топология (gossip-карта)", style = MaterialTheme.typography.titleMedium)
            Text("Узел: ${localIdentity.userId}")
            Text("Нод в карте: ${topology.nodes.size}, ребер: ${topology.edges.size}")
            if (topology.edges.isEmpty()) {
                Text("Карта пока пустая")
            } else {
                topology.edges.take(12).forEach { edge ->
                    Text("• ${edge.fromNode} -> ${edge.toNode}")
                }
                if (topology.edges.size > 12) Text("... еще ${topology.edges.size - 12} связей")
            }
        }
    }
}

@Composable
private fun rememberRuntimePermissions(): () -> Unit {
    val context = LocalContext.current
    val permissions = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
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

