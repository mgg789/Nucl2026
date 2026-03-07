package com.peerdone.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.peerdone.app.core.message.OutboundContent
import com.peerdone.app.core.transport.TransportHealth
import com.peerdone.app.core.transport.TransportRegistry
import com.peerdone.app.core.transport.TransportType
import com.peerdone.app.core.transport.DeliveryClass
import com.peerdone.app.domain.AccessPolicy
import com.peerdone.app.service.SendOrchestrator
import com.peerdone.app.data.RouterTransportAdapter
import com.peerdone.app.data.DeliveryState
import com.peerdone.app.data.NearbyTransportAdapter
import com.peerdone.app.data.OutboundMessageRecord
import com.peerdone.app.core.transport.TransportAdapter
import com.peerdone.app.core.transport.StubTransportAdapter
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import com.peerdone.app.data.PreferencesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peerdone.app.R
import com.peerdone.app.di.LocalDeviceIdentity
import com.peerdone.app.di.LocalNearbyClient
import com.peerdone.app.ui.theme.PeerDoneBackground
import com.peerdone.app.ui.theme.PeerDoneBlue
import com.peerdone.app.ui.theme.PeerDoneDarkGray
import com.peerdone.app.ui.theme.PeerDoneGray
import com.peerdone.app.ui.theme.PeerDoneInputFieldSolid
import com.peerdone.app.ui.theme.PeerDoneOnline
import com.peerdone.app.ui.theme.PeerDonePrimary
import com.peerdone.app.ui.theme.PeerDoneStopButton
import com.peerdone.app.ui.theme.PeerDoneStopButtonText
import com.peerdone.app.ui.theme.PeerDoneSurface
import com.peerdone.app.ui.theme.PeerDoneTextPrimary
import com.peerdone.app.ui.theme.PeerDoneTextSecondary
import com.peerdone.app.ui.theme.PeerDoneWhite

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val identityStore = LocalDeviceIdentity.current
    val nearbyClient = LocalNearbyClient.current
    val localIdentity = remember { identityStore.getOrCreate() }
    var displayName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val prefs = PreferencesStore(context)
        displayName = withContext(Dispatchers.IO) {
            listOf(
                prefs.userFirstName.first(),
                prefs.userLastName.first(),
                prefs.userNickname.first()
            ).filter { it.isNotBlank() }.joinToString(" ").take(30).ifBlank { null }
        }
    }

    val isRunning by nearbyClient.isRunning.collectAsState()
    val topology by nearbyClient.topology.collectAsState()
    val rawPeerInfos by nearbyClient.connectedPeerInfos.collectAsState()
    val deliveryMetrics by nearbyClient.deliveryMetrics.collectAsState()
    val outboundRecords by nearbyClient.outboundRecords.collectAsState()
    val peerInfos = remember(rawPeerInfos) { rawPeerInfos.distinctBy { it.userId } }
    var showDevicesSheet by remember { mutableStateOf(false) }
    var showPacketsSheet by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    if (showDevicesSheet) {
        ModalBottomSheet(
            onDismissRequest = { showDevicesSheet = false },
            sheetState = sheetState,
            containerColor = PeerDoneSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Устройства в сети",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PeerDoneTextPrimary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                HorizontalDivider(color = PeerDoneGray.copy(alpha = 0.3f))
                Spacer(modifier = Modifier.height(12.dp))
                if (peerInfos.isEmpty()) {
                    Text(
                        text = "Нет подключённых устройств",
                        fontSize = 14.sp,
                        color = PeerDoneTextSecondary,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                } else {
                    peerInfos.forEach { peer ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(PeerDonePrimary.copy(alpha = 0.3f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = peer.userId.firstOrNull()?.uppercase() ?: "?",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = PeerDonePrimary
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = peer.displayName.ifBlank { peer.deviceModel }.ifBlank { peer.userId.take(16) }.ifBlank { "Устройство" },
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = PeerDoneTextPrimary
                                    )
                                    Text(
                                        text = peer.userId.take(20) + if (peer.userId.length > 20) "…" else "",
                                        fontSize = 12.sp,
                                        color = PeerDoneTextSecondary
                                    )
                                }
                            }
                            Text(
                                text = if (deliveryMetrics.avgAckRttMs > 0) "~${deliveryMetrics.avgAckRttMs} мс" else "—",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = PeerDonePrimary
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPacketsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showPacketsSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = PeerDoneSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = "Последние 20 пакетов",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PeerDoneTextPrimary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                HorizontalDivider(color = PeerDoneGray.copy(alpha = 0.3f))
                Spacer(modifier = Modifier.height(12.dp))
                val packets = outboundRecords.take(20)
                if (packets.isEmpty()) {
                    Text(
                        text = "Нет отправленных пакетов",
                        fontSize = 14.sp,
                        color = PeerDoneTextSecondary,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(packets) { rec ->
                            PacketItem(record = rec)
                        }
                    }
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PeerDoneBackground)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(60.dp))

        Text(
            text = "PeerDone",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFFAE76E9),
                            Color(0xFFA5B4E9)
                        )
                    ),
                    shape = RoundedCornerShape(6.dp)
                )
                .padding(horizontal = 12.dp, vertical = 4.dp),
            color = PeerDoneWhite
        )

        Spacer(modifier = Modifier.height(30.dp))

        NetworkStatCard(
            label = "Устройств в сети",
            value = if (!isRunning) "—" else if (peerInfos.isEmpty()) "Идёт поиск..." else "${peerInfos.size}",
            valueColor = PeerDonePrimary,
            onClick = { showDevicesSheet = true }
        )

        Spacer(modifier = Modifier.height(12.dp))

        NetworkStatCard(
            label = "Шифрование",
            value = "Подключено",
            valueColor = PeerDoneOnline
        )

        Spacer(modifier = Modifier.height(12.dp))

        val scope = rememberCoroutineScope()
        val prefsStore = remember(context) { PreferencesStore(context) }
        val preferredTransport by prefsStore.preferredTransport.collectAsState(initial = "auto")
        val transportLabel = when (preferredTransport) {
            "nearby" -> "Nearby (BLE + Wi‑Fi)"
            "wifi_direct" -> "WiFi Direct"
            "lan" -> "LAN (совместимость с iOS)"
            "all" -> "Все протоколы (Nearby + WiFi + LAN)"
            else -> "Авто (Nearby)"
        }
        NetworkStatCard(
            label = "Транспорт",
            value = transportLabel,
            valueColor = PeerDoneBlue
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Протокол общения",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = PeerDoneTextSecondary
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("auto" to "Авто", "nearby" to "Nearby", "wifi_direct" to "WiFi Direct", "lan" to "LAN", "all" to "Все").forEach { (value, label) ->
                val selected = preferredTransport == value
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (selected) PeerDonePrimary.copy(alpha = 0.25f) else PeerDoneSurface,
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            scope.launch {
                                prefsStore.setPreferredTransport(value)
                            }
                        }
                ) {
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        color = if (selected) PeerDonePrimary else PeerDoneTextSecondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Метрики",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = PeerDoneTextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        NetworkStatCard(
            label = "Пинг (RTT)",
            value = if (deliveryMetrics.avgAckRttMs > 0) "${deliveryMetrics.avgAckRttMs} мс" else "—",
            valueColor = PeerDonePrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        NetworkStatCard(
            label = "RTT p95",
            value = if (deliveryMetrics.p95AckRttMs > 0) "${deliveryMetrics.p95AckRttMs} мс" else "—",
            valueColor = PeerDoneGray
        )
        Spacer(modifier = Modifier.height(8.dp))
        NetworkStatCard(
            label = "Доставка",
            value = "Отпр: ${deliveryMetrics.sentCount} · Доставлено: ${deliveryMetrics.ackedCount} · Потери: ${deliveryMetrics.lossPercent}%",
            valueColor = PeerDoneDarkGray,
            onClick = { showPacketsSheet = true }
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Пинг обновляется после доставки сообщения (получен ACK).",
            fontSize = 11.sp,
            color = PeerDoneGray,
            modifier = Modifier.padding(horizontal = 4.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        val adapters = remember(nearbyClient) {
            listOf<TransportAdapter>(
                RouterTransportAdapter(nearbyClient),
                StubTransportAdapter(
                    type = TransportType.BLUETOOTH_LE,
                    staticHealth = TransportHealth(TransportType.BLUETOOTH_LE, false, 250, 800, 7, 4),
                ),
            )
        }
        val adaptersByType = remember(adapters) { adapters.associateBy { it.type } }
        val transportRegistry = remember(adapters) {
            TransportRegistry().apply { adapters.forEach { register { it.health() } } }
        }
        val sendOrchestrator = remember(adaptersByType, transportRegistry, prefsStore) {
            SendOrchestrator(
                adaptersByType,
                transportRegistry,
                preferredTransport = {
                    when (prefsStore.preferredTransportSync()) {
                        "wifi_direct" -> TransportType.WIFI_DIRECT
                        "nearby" -> TransportType.NEARBY
                        "lan" -> TransportType.LAN_P2P
                        "all" -> null
                        else -> null
                    }
                },
            )
        }
        var rateLimitTestRunning by remember { mutableStateOf(false) }

        Button(
            onClick = {
                if (rateLimitTestRunning) return@Button
                rateLimitTestRunning = true
                scope.launch {
                    val policy = AccessPolicy()
                    repeat(50) { i ->
                        sendOrchestrator.enqueueAndTrySend(
                            sender = localIdentity,
                            content = OutboundContent.Text("rate-test #${i + 1}"),
                            policy = policy,
                            deliveryClass = DeliveryClass.INTERACTIVE,
                        )
                        kotlinx.coroutines.delay(80)
                    }
                    rateLimitTestRunning = false
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PeerDoneGray),
            enabled = !rateLimitTestRunning
        ) {
            Text(
                text = if (rateLimitTestRunning) "Отправка…" else "Тест rate limit (50 сообщений)",
                fontSize = 14.sp,
                color = PeerDoneWhite
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Поиск устройств: BLE. Обмен данными: Wi‑Fi/LAN. Один протокол — Google Nearby Connections.",
            fontSize = 12.sp,
            color = PeerDoneGray,
            modifier = Modifier.padding(horizontal = 4.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (isRunning) PeerDoneOnline else PeerDoneGray)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = if (isRunning) "Сеть активна" else "Сеть неактивна",
                fontSize = 14.sp,
                color = if (isRunning) PeerDoneOnline else PeerDoneGray
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Карта устройств",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = PeerDoneTextPrimary
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (peerInfos.isEmpty() && topology.edges.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_network),
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = PeerDoneGray
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Нет активных соединений",
                        color = PeerDoneGray,
                        fontSize = 14.sp
                    )
                }
            }
        } else {
            peerInfos.forEach { peer ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = PeerDoneSurface
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(PeerDonePrimary.copy(alpha = 0.3f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = peer.userId.firstOrNull()?.uppercase() ?: "?",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = PeerDonePrimary
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = peer.displayName.ifBlank { peer.deviceModel }.ifBlank { peer.userId.take(16) }.ifBlank { "Устройство" },
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = PeerDoneTextPrimary
                            )
                            Text(
                                text = peer.userId.take(14) + if (peer.userId.length > 14) "…" else "",
                                fontSize = 12.sp,
                                color = PeerDoneGray
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(PeerDoneOnline)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "В сети",
                                    fontSize = 11.sp,
                                    color = PeerDoneOnline
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (isRunning) {
                topology.edges.take(20).forEach { edge ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = PeerDoneSurface
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(PeerDoneOnline)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "${edge.fromNode.take(8)}... → ${edge.toNode.take(8)}...",
                            fontSize = 14.sp,
                            color = PeerDoneTextPrimary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                if (isRunning) {
                    nearbyClient.stop()
                } else {
                    nearbyClient.start(localIdentity, displayName)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) PeerDoneStopButton else PeerDonePrimary
            )
        ) {
            Text(
                text = if (isRunning) "Остановить сеть" else "Запустить сеть",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = if (isRunning) PeerDoneStopButtonText else PeerDoneWhite
            )
        }

        Spacer(modifier = Modifier.height(100.dp))
    }
}

@Composable
private fun PacketItem(record: OutboundMessageRecord) {
    val stateColor = when (record.state) {
        DeliveryState.QUEUED -> PeerDoneGray
        DeliveryState.SENT -> PeerDoneBlue
        DeliveryState.ACKED -> PeerDoneOnline
        DeliveryState.FAILED -> PeerDoneStopButton
    }
    val stateText = when (record.state) {
        DeliveryState.QUEUED -> "В очереди"
        DeliveryState.SENT -> "Отправлен"
        DeliveryState.ACKED -> "Доставлен"
        DeliveryState.FAILED -> "Потерян"
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = PeerDoneInputFieldSolid
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.preview.take(40).let { if (record.preview.length > 40) "$it…" else it },
                    fontSize = 14.sp,
                    color = PeerDoneTextPrimary,
                    maxLines = 2
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append("ID: ${record.id.take(12)}… · ${record.updatedAtMs.toTimeString()}")
                        record.payloadSizeBytes?.let { append(" · ${formatPacketSize(it)}") }
                    },
                    fontSize = 11.sp,
                    color = PeerDoneTextSecondary
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = stateText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = stateColor
                )
                if (record.retries > 0) {
                    Text(
                        text = "Повторов: ${record.retries}",
                        fontSize = 11.sp,
                        color = PeerDoneGray
                    )
                }
            }
        }
    }
}

private fun Long.toTimeString(): String {
    val s = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
    return s.format(java.util.Date(this))
}

/** Форматирует размер в Б/Кб/Мб. */
private fun formatPacketSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes Б"
    bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f Кб", bytes / 1024.0)
    else -> String.format(java.util.Locale.US, "%.2f Мб", bytes / (1024.0 * 1024.0))
}

@Composable
private fun NetworkStatCard(
    label: String,
    value: String,
    valueColor: Color = PeerDoneDarkGray,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
            ),
        shape = RoundedCornerShape(40.dp),
        color = PeerDoneInputFieldSolid
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = value,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = valueColor
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = label,
                    fontSize = 13.sp,
                    color = PeerDoneDarkGray
                )
            }
            Icon(
                painter = painterResource(id = R.drawable.ic_info),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = PeerDoneGray
            )
        }
    }
}
