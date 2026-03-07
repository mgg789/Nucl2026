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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.peerdone.app.R
import com.peerdone.app.core.audio.JitterStats
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import com.peerdone.app.core.call.CallManager
import com.peerdone.app.core.call.CallState
import com.peerdone.app.ui.components.CallQualityIndicator
import com.peerdone.app.ui.theme.PeerDoneBackground
import com.peerdone.app.ui.theme.PeerDoneDarkGray
import com.peerdone.app.ui.theme.PeerDoneGray
import com.peerdone.app.ui.theme.PeerDonePrimary
import com.peerdone.app.ui.theme.PeerDoneWhite
import kotlinx.coroutines.delay

@Composable
fun ActiveCallScreen(
    callManager: CallManager,
    onCallEnded: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeCall by callManager.activeCall.collectAsState()
    val callState by callManager.callState.collectAsState()
    val remoteVideoTrack by callManager.remoteVideoTrack.collectAsState()
    var callDuration by remember { mutableLongStateOf(0L) }
    var jitterStats by remember { mutableStateOf(JitterStats(0, 0, 0, 0, 0f)) }
    var lastRemoteTrack by remember { mutableStateOf<org.webrtc.VideoTrack?>(null) }
    var localSinkSet by remember { mutableStateOf(false) }
    val eglBase = remember { EglBase.create() }
    DisposableEffect(Unit) { onDispose { eglBase.release() } }
    
    LaunchedEffect(callState) {
        if (callState == CallState.ACTIVE) {
            while (true) {
                callDuration = callManager.getCallDuration()
                jitterStats = callManager.getMetrics().jitterStats
                delay(1000)
            }
        }
    }
    
    LaunchedEffect(callState) {
        if (callState == CallState.ENDED || callState == CallState.IDLE) {
            if (activeCall == null) {
                delay(500)
                onCallEnded()
            }
        }
    }
    
    val peerName = activeCall?.peerName ?: "Unknown"
    val isMuted = activeCall?.isMuted ?: false
    val isSpeakerOn = activeCall?.isSpeakerOn ?: false
    
    DisposableEffect(Unit) {
        onDispose { callManager.release() }
    }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PeerDoneBackground)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            IconButton(
                onClick = {
                    callManager.endCall()
                    onCallEnded()
                }
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_back),
                    contentDescription = "Закрыть",
                    tint = PeerDoneWhite
                )
            }
            Text(
                text = "Закрыть",
                fontSize = 16.sp,
                color = PeerDoneGray
            )
        }
        
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(16.dp))
            ) {
                if (remoteVideoTrack != null) {
                    AndroidView(
                        factory = { ctx ->
                            SurfaceViewRenderer(ctx).apply {
                                init(eglBase.eglBaseContext, null)
                                setZOrderMediaOverlay(false)
                            }
                        },
                        update = { renderer ->
                            if (remoteVideoTrack != lastRemoteTrack) {
                                lastRemoteTrack?.removeSink(renderer)
                                lastRemoteTrack = remoteVideoTrack
                                remoteVideoTrack?.addSink(renderer)
                            }
                        },
                        onRelease = { renderer ->
                            lastRemoteTrack?.removeSink(renderer)
                            lastRemoteTrack = null
                            renderer.release()
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(60.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(120.dp)
                                .clip(CircleShape)
                                .background(PeerDoneDarkGray),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = peerName.firstOrNull()?.uppercase() ?: "?",
                                fontSize = 48.sp,
                                fontWeight = FontWeight.Bold,
                                color = PeerDoneWhite
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(PeerDoneDarkGray)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            SurfaceViewRenderer(ctx).apply {
                                init(eglBase.eglBaseContext, null)
                                setZOrderMediaOverlay(true)
                                setMirror(true)
                            }
                        },
                        update = { renderer ->
                            if (!localSinkSet) {
                                callManager.setLocalVideoSink(renderer)
                                localSinkSet = true
                            }
                        },
                        onRelease = { renderer ->
                            callManager.setLocalVideoSink(null)
                            localSinkSet = false
                            renderer.release()
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = peerName,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = PeerDoneWhite,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = when (callState) {
                    CallState.OUTGOING_OFFER_SENT -> "Вызов..."
                    CallState.INCOMING_OFFER_RECEIVED -> "Входящий вызов"
                    CallState.CONNECTING -> "Соединение..."
                    CallState.ACTIVE -> formatCallDuration(callDuration)
                    CallState.ENDED, CallState.IDLE -> "Завершён"
                    else -> ""
                },
                fontSize = 18.sp,
                color = PeerDoneGray,
                textAlign = TextAlign.Center
            )
            
            if (callState == CallState.ACTIVE) {
                Spacer(modifier = Modifier.height(24.dp))
                CallQualityIndicator(stats = jitterStats)
            }
        }
        
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                CallControlButton(
                    iconRes = if (isMuted) R.drawable.ic_mute else R.drawable.ic_mic,
                    label = if (isMuted) "Вкл. звук" else "Выкл. звук",
                    isActive = isMuted,
                    onClick = { callManager.toggleMute() }
                )
                
                CallControlButton(
                    iconRes = R.drawable.ic_speaker,
                    label = if (isSpeakerOn) "Телефон" else "Динамик",
                    isActive = isSpeakerOn,
                    onClick = { callManager.toggleSpeaker() }
                )
            }
            
            Spacer(modifier = Modifier.height(48.dp))
            
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFF4444))
                    .clickable { callManager.endCall() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_call_end),
                    contentDescription = "End Call",
                    modifier = Modifier.size(32.dp),
                    tint = PeerDoneWhite
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "Завершить",
                fontSize = 14.sp,
                color = PeerDoneGray
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun CallControlButton(
    iconRes: Int,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(if (isActive) PeerDonePrimary else PeerDoneDarkGray)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = label,
                modifier = Modifier.size(24.dp),
                tint = PeerDoneWhite
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = label,
            fontSize = 12.sp,
            color = PeerDoneGray
        )
    }
}

@Composable
fun IncomingCallScreen(
    callerName: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PeerDoneBackground)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(modifier = Modifier.height(80.dp))
        
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(PeerDoneDarkGray),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = callerName.firstOrNull()?.uppercase() ?: "?",
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    color = PeerDoneWhite
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = callerName,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = PeerDoneWhite,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "Входящий звонок",
                fontSize = 18.sp,
                color = PeerDoneGray,
                textAlign = TextAlign.Center
            )
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFF4444))
                        .clickable(onClick = onDecline),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_call_end),
                        contentDescription = "Decline",
                        modifier = Modifier.size(32.dp),
                        tint = PeerDoneWhite
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Отклонить",
                    fontSize = 14.sp,
                    color = PeerDoneGray
                )
            }
            
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(PeerDonePrimary)
                        .clickable(onClick = onAccept),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_call),
                        contentDescription = "Accept",
                        modifier = Modifier.size(32.dp),
                        tint = PeerDoneWhite
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Ответить",
                    fontSize = 14.sp,
                    color = PeerDoneGray
                )
            }
        }
        
        Spacer(modifier = Modifier.height(48.dp))
    }
}

private fun formatCallDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
