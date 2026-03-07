package com.peerdone.app.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.sp
import com.peerdone.app.R
import com.peerdone.app.di.LocalDeviceIdentity
import com.peerdone.app.di.LocalNearbyClient
import com.peerdone.app.ui.theme.PeerDoneBackground
import com.peerdone.app.ui.theme.PeerDoneChipActive
import com.peerdone.app.ui.theme.PeerDoneChipInactive
import com.peerdone.app.ui.theme.PeerDoneDarkGray
import com.peerdone.app.ui.theme.PeerDoneGray
import com.peerdone.app.ui.theme.PeerDoneLightGray
import com.peerdone.app.ui.theme.PeerDoneOnline
import com.peerdone.app.ui.theme.PeerDonePrimary
import com.peerdone.app.ui.theme.PeerDoneTextMuted
import com.peerdone.app.ui.theme.PeerDoneWhite
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class ChatFilter {
    GROUPS, CHATS, SERVICES
}

data class ChatPreviewItem(
    val peerId: String,
    val displayName: String,
    val lastMessage: String,
    val lastTime: String,
    val isOnline: Boolean,
    val unreadCount: Int
)

@Composable
fun ChatListScreen(
    onChatClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val identityStore = LocalDeviceIdentity.current
    val nearbyClient = LocalNearbyClient.current

    val localIdentity = remember { identityStore.getOrCreate() }
    val connectedPeers by nearbyClient.connectedPeerInfos.collectAsState()
    val incoming by nearbyClient.incomingMessages.collectAsState()

    var selectedFilter by remember { mutableStateOf(ChatFilter.CHATS) }

    fun normalizePeerId(s: String): String = s.substringBefore("|").trim()
    val localUserId = normalizePeerId(localIdentity.userId)
    val chatPreviews = remember(incoming, connectedPeers, localIdentity) {
        val peerIds = connectedPeers.map { normalizePeerId(it.userId) }
            .filter { it.isNotBlank() && it != localUserId }
            .distinct()
            .sorted()
        val peerInfoById = connectedPeers.associateBy { normalizePeerId(it.userId) }

        peerIds.map { peerId ->
            val info = peerInfoById[peerId]
            val displayName = when {
                !info?.displayName.isNullOrBlank() -> info!!.displayName
                !info?.deviceModel.isNullOrBlank() -> info!!.deviceModel
                else -> peerId.take(16)
            }
            val peerMessages = incoming.filter { normalizePeerId(it.envelope.senderUserId) == peerId }
            val lastMsg = peerMessages.lastOrNull()
            ChatPreviewItem(
                peerId = peerId,
                displayName = displayName,
                lastMessage = lastMsg?.contentSummary ?: "Нет сообщений",
                lastTime = lastMsg?.envelope?.timestampMs?.toTimeString() ?: "--:--",
                isOnline = true,
                unreadCount = peerMessages.count { it.accessGranted }.coerceAtMost(99)
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PeerDoneBackground)
    ) {
        Spacer(modifier = Modifier.height(60.dp))

        Text(
            text = "PeerDone",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .padding(horizontal = 20.dp)
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

        Spacer(modifier = Modifier.height(20.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 20.dp)
        ) {
            item {
                FilterChip(
                    text = "Группы",
                    isSelected = selectedFilter == ChatFilter.GROUPS,
                    onClick = { selectedFilter = ChatFilter.GROUPS }
                )
            }
            item {
                FilterChip(
                    text = "Чаты",
                    isSelected = selectedFilter == ChatFilter.CHATS,
                    onClick = { selectedFilter = ChatFilter.CHATS }
                )
            }
            item {
                FilterChip(
                    text = "Сервисы",
                    isSelected = selectedFilter == ChatFilter.SERVICES,
                    onClick = { selectedFilter = ChatFilter.SERVICES }
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp)
        ) {
            items(chatPreviews) { chat ->
                ChatPreviewCard(
                    chat = chat,
                    onClick = { onChatClick(chat.peerId) }
                )
                HorizontalDivider(
                    color = PeerDoneWhite.copy(alpha = 0.08f),
                    thickness = 1.dp,
                    modifier = Modifier.padding(start = 68.dp)
                )
            }

            if (chatPreviews.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            androidx.compose.material3.Icon(
                                painter = painterResource(id = R.drawable.ic_chat),
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = PeerDoneGray
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Text(
                                text = "Нет чатов",
                                color = PeerDoneWhite,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Найдите людей в сети чтобы начать общение",
                                color = PeerDoneGray,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (isSelected) PeerDoneChipActive else Color.Transparent
    val textColor = if (isSelected) PeerDoneWhite else PeerDoneChipInactive
    val borderColor = if (isSelected) Color.Transparent else PeerDoneChipInactive

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(100.dp))
            .then(
                if (!isSelected) {
                    Modifier.border(1.5.dp, borderColor, RoundedCornerShape(100.dp))
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(100.dp),
        color = backgroundColor
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            color = textColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ChatPreviewCard(
    chat: ChatPreviewItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(52.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(PeerDoneLightGray),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = chat.displayName.firstOrNull()?.uppercase() ?: "?",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PeerDoneDarkGray
                )
            }

            if (chat.isOnline) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(PeerDoneBackground)
                        .padding(2.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(PeerDoneOnline)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chat.displayName,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                color = PeerDoneWhite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = chat.lastMessage,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = PeerDoneTextMuted,
                fontSize = 14.sp
            )
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = chat.lastTime,
                fontSize = 12.sp,
                color = if (chat.unreadCount > 0) PeerDonePrimary else PeerDoneDarkGray
            )

            if (chat.unreadCount > 0) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(PeerDonePrimary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${chat.unreadCount}",
                        color = PeerDoneLightGray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

private fun Long.toTimeString(): String {
    val format = SimpleDateFormat("HH:mm", Locale.getDefault())
    return format.format(Date(this))
}
