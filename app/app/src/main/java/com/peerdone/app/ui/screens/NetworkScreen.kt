package com.peerdone.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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
import com.peerdone.app.ui.theme.PeerDoneWhite

@Composable
fun NetworkScreen(
    modifier: Modifier = Modifier
) {
    val identityStore = LocalDeviceIdentity.current
    val nearbyClient = LocalNearbyClient.current
    val localIdentity = remember { identityStore.getOrCreate() }

    val isRunning by nearbyClient.isRunning.collectAsState()
    val topology by nearbyClient.topology.collectAsState()
    val rawPeerInfos by nearbyClient.connectedPeerInfos.collectAsState()
    val peerInfos = remember(rawPeerInfos) { rawPeerInfos.distinctBy { it.userId } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PeerDoneBackground)
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
            label = "Узлов в сети",
            value = if (topology.nodes.isEmpty()) "Идёт подсчёт..." else "${topology.nodes.size}",
            valueColor = PeerDonePrimary
        )

        Spacer(modifier = Modifier.height(12.dp))

        NetworkStatCard(
            label = "Шифрование",
            value = "Подключено",
            valueColor = PeerDoneOnline
        )

        Spacer(modifier = Modifier.height(12.dp))

        NetworkStatCard(
            label = "Транспорт",
            value = "Nearby (BLE + Wi‑Fi)",
            valueColor = PeerDoneBlue
        )

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
            color = PeerDoneWhite
        )

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(peerInfos) { peer ->
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
                                color = PeerDoneWhite
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = peer.deviceModel.ifBlank { peer.userId.take(16) }.ifBlank { "Устройство" },
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = PeerDoneWhite
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
            }

            items(topology.edges.take(50)) { edge ->
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
                            color = PeerDoneWhite
                        )
                    }
                }
            }

            if (peerInfos.isEmpty() && topology.edges.isEmpty()) {
                item {
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
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                if (isRunning) {
                    nearbyClient.stop()
                } else {
                    nearbyClient.start(localIdentity)
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
private fun NetworkStatCard(
    label: String,
    value: String,
    valueColor: Color = PeerDoneDarkGray,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
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
