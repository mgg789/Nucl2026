package com.peerdone.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.peerdone.app.core.message.ContentCodec
import com.peerdone.app.core.message.ContentKind
import com.peerdone.app.core.message.OutboundContent
import com.peerdone.app.data.ReceivedMeshMessage
import com.peerdone.app.di.LocalCallManager
import com.peerdone.app.di.LocalNearbyClient
import kotlin.collections.mutableSetOf

/**
 * Обрабатывает входящие CallSignal и AudioPacket всегда, независимо от текущего экрана.
 * Должен быть в дереве композиции на уровне корня (например в MainActivity),
 * иначе при открытом экране звонка ответ "answer" не придёт в CallManager.
 */
@Composable
fun CallSignalHandler() {
    val nearbyClient = LocalNearbyClient.current
    val callManager = LocalCallManager.current
    val incoming by nearbyClient.incomingMessages.collectAsState()
    val processedIds = remember { mutableSetOf<String>() }

    LaunchedEffect(incoming) {
        incoming.forEach { msg: ReceivedMeshMessage ->
            if (msg.contentKind != ContentKind.CALL_SIGNAL && msg.contentKind != ContentKind.AUDIO_PACKET) return@forEach
            if (!processedIds.add(msg.envelope.id)) return@forEach
            val raw = msg.decryptedText ?: return@forEach
            val content = ContentCodec.decode(raw) ?: return@forEach
            val peerId = msg.envelope.senderUserId
            val peerName = peerId.take(16)
            when (content) {
                is OutboundContent.CallSignal -> {
                    callManager.handleIncomingSignal(
                        callId = content.callId,
                        phase = content.phase,
                        peerId = peerId,
                        peerName = peerName,
                        sdpOrIce = content.sdpOrIce
                    )
                }
                is OutboundContent.AudioPacket -> {
                    callManager.handleAudioPacket(
                        callId = content.callId,
                        sequenceNumber = content.sequenceNumber,
                        audioDataBase64 = content.audioDataBase64
                    )
                }
                else -> { }
            }
        }
    }
}
