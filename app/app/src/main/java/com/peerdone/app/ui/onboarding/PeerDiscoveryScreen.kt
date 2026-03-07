package com.peerdone.app.ui.onboarding

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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
import com.peerdone.app.ui.theme.PeerDoneGray
import com.peerdone.app.ui.theme.PeerDoneInputFieldSolid
import com.peerdone.app.ui.theme.PeerDoneLightGray
import com.peerdone.app.ui.theme.PeerDonePrimary
import com.peerdone.app.ui.theme.PeerDoneStopButton
import com.peerdone.app.ui.theme.PeerDoneStopButtonText
import com.peerdone.app.ui.theme.PeerDoneTextDark
import com.peerdone.app.ui.theme.PeerDoneWhite

@Composable
fun PeerDiscoveryScreen(
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val identityStore = LocalDeviceIdentity.current
    val nearbyClient = LocalNearbyClient.current
    val localIdentity = remember { identityStore.getOrCreate() }

    var isSearching by remember { mutableStateOf(true) }
    val topology by nearbyClient.topology.collectAsState()
    val discoveredPeers = remember(topology) {
        topology.nodes.filter { it != localIdentity.userId }.distinct()
    }
    val addedPeers = remember { mutableStateListOf<String>() }

    DisposableEffect(Unit) {
        nearbyClient.start(localIdentity, null)
        onDispose { nearbyClient.stop() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PeerDoneBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(60.dp))

            Text(
                text = "PeerDone",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color(0xFFAE76E9), Color(0xFFA5B4E9))
                    ),
                    shape = RoundedCornerShape(6.dp)
                ).padding(horizontal = 12.dp, vertical = 4.dp),
                color = PeerDoneWhite
            )

            Spacer(modifier = Modifier.height(48.dp))

            if (isSearching) {
                SearchingAnimation()
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "Поиск людей",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = PeerDoneWhite
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Убедитесь, что вы находитесь в одной сети",
                fontSize = 14.sp,
                color = PeerDoneGray
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { onComplete() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PeerDoneLightGray
                )
            ) {
                Text(
                    text = "Найдите друзей",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = PeerDonePrimary
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(discoveredPeers) { peerId ->
                    val isAdded = peerId in addedPeers
                    DiscoveredPeerCard(
                        peerId = peerId,
                        isAdded = isAdded,
                        onAddClick = {
                            if (!isAdded) addedPeers.add(peerId)
                        }
                    )
                }

                if (discoveredPeers.isEmpty()) {
                    item {
                        Text(
                            text = "Поиск устройств...",
                            color = PeerDoneGray,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    if (isSearching) {
                        nearbyClient.stop()
                        isSearching = false
                    } else {
                        nearbyClient.start(localIdentity, null)
                        isSearching = true
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PeerDoneStopButton
                )
            ) {
                Text(
                    text = if (isSearching) "Остановить" else "Продолжить поиск",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PeerDoneStopButtonText
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onComplete,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PeerDonePrimary
                )
            ) {
                Text(
                    text = "Продолжить",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PeerDoneWhite
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SearchingAnimation(
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "search_animation")

    val scale1 by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scale1"
    )

    val scale2 by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, delayMillis = 500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scale2"
    )

    val scale3 by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, delayMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scale3"
    )

    Box(
        modifier = modifier.size(180.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(180.dp)
                .scale(scale1)
                .clip(CircleShape)
                .background(PeerDonePrimary.copy(alpha = 0.1f))
        )
        Box(
            modifier = Modifier
                .size(180.dp)
                .scale(scale2)
                .clip(CircleShape)
                .background(PeerDonePrimary.copy(alpha = 0.15f))
        )
        Box(
            modifier = Modifier
                .size(180.dp)
                .scale(scale3)
                .clip(CircleShape)
                .background(PeerDonePrimary.copy(alpha = 0.2f))
        )
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(PeerDoneLightGray),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_search),
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = PeerDonePrimary
            )
        }
    }
}

@Composable
private fun DiscoveredPeerCard(
    peerId: String,
    isAdded: Boolean,
    onAddClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50.dp))
            .background(PeerDoneInputFieldSolid)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(PeerDoneWhite),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = peerId.firstOrNull()?.uppercase() ?: "?",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = PeerDoneTextDark
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = peerId.take(12),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = PeerDoneTextDark,
            modifier = Modifier.weight(1f)
        )

        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (isAdded) PeerDonePrimary else Color.Transparent)
                .border(
                    width = if (isAdded) 0.dp else 1.dp,
                    color = if (isAdded) Color.Transparent else PeerDoneGray,
                    shape = CircleShape
                )
                .clickable { onAddClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = if (isAdded) R.drawable.ic_check else R.drawable.ic_plus),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (isAdded) PeerDoneWhite else PeerDoneGray
            )
        }
    }
}
