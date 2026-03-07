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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peerdone.app.R
import com.peerdone.app.data.CallHistoryItem
import com.peerdone.app.data.CallHistoryStore
import com.peerdone.app.data.CallType
import com.peerdone.app.ui.theme.PeerDoneBackground
import com.peerdone.app.ui.theme.PeerDoneDarkGray
import com.peerdone.app.ui.theme.PeerDoneGray
import com.peerdone.app.ui.theme.PeerDoneInputFieldSolid
import com.peerdone.app.ui.theme.PeerDoneOnline
import com.peerdone.app.ui.theme.PeerDonePrimary
import com.peerdone.app.ui.theme.PeerDoneWhite
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CallsScreen(
    onCallClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val callHistoryStore = remember { CallHistoryStore(context) }
    val callHistory by callHistoryStore.history.collectAsState(initial = emptyList())

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

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(callHistory) { call ->
                CallHistoryCard(
                    call = call,
                    onClick = { onCallClick(call.peerId) }
                )
            }

            if (callHistory.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_call),
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = PeerDoneGray
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Нет истории звонков",
                                fontSize = 16.sp,
                                color = PeerDoneGray
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Позвоните друзьям из чата",
                                fontSize = 14.sp,
                                color = PeerDoneGray.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(100.dp))
    }
}

@Composable
private fun CallHistoryCard(
    call: CallHistoryItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(40.dp),
        color = PeerDoneInputFieldSolid
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(PeerDoneWhite),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = call.peerId.firstOrNull()?.uppercase() ?: "?",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = PeerDoneDarkGray
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = call.peerId.take(16),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = PeerDoneDarkGray
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = getCallTypeIcon(call.type)),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = getCallTypeColor(call.type)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = getCallTypeText(call.type),
                        fontSize = 13.sp,
                        color = getCallTypeColor(call.type)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Icon(
                        painter = painterResource(id = R.drawable.ic_clock),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = PeerDoneGray
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = formatCallTime(call.timestampMs),
                        fontSize = 13.sp,
                        color = PeerDoneGray
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(PeerDoneOnline.copy(alpha = 0.15f))
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_call),
                    contentDescription = "Call",
                    modifier = Modifier.size(22.dp),
                    tint = PeerDoneOnline
                )
            }
        }
    }
}

private fun getCallTypeIcon(type: CallType): Int {
    return when (type) {
        CallType.OUTGOING -> R.drawable.ic_call_outgoing
        CallType.INCOMING -> R.drawable.ic_call_incoming
        CallType.MISSED -> R.drawable.ic_call_missed
    }
}

private fun getCallTypeColor(type: CallType): Color {
    return when (type) {
        CallType.OUTGOING -> PeerDonePrimary
        CallType.INCOMING -> PeerDoneOnline
        CallType.MISSED -> Color(0xFFE94E4E)
    }
}

private fun getCallTypeText(type: CallType): String {
    return when (type) {
        CallType.OUTGOING -> "Исходящий"
        CallType.INCOMING -> "Входящий"
        CallType.MISSED -> "Пропущенный"
    }
}

private fun formatCallTime(timestampMs: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestampMs
    val dayMs = 24 * 60 * 60 * 1000L

    return when {
        diff < dayMs -> SimpleDateFormat("HH:mm", Locale.forLanguageTag("ru")).format(Date(timestampMs))
        diff < 7 * dayMs -> SimpleDateFormat("EEE", Locale.forLanguageTag("ru")).format(Date(timestampMs))
        else -> SimpleDateFormat("dd MMM", Locale.forLanguageTag("ru")).format(Date(timestampMs))
    }
}
