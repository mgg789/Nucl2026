package com.peerdone.app.core.call

import android.content.Context
import android.media.AudioManager
import com.peerdone.app.core.audio.AudioCaptureManager
import com.peerdone.app.core.audio.AudioPlaybackManager
import com.peerdone.app.core.audio.JitterStats
import com.peerdone.app.core.message.OutboundContent
import com.peerdone.app.data.NearbyMeshClient
import com.peerdone.app.domain.LocalIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.VideoTrack
import android.os.Handler
import android.os.Looper
import java.util.UUID

enum class CallDirection {
    OUTGOING,
    INCOMING
}

data class ActiveCall(
    val callId: String,
    val peerId: String,
    val peerName: String,
    val direction: CallDirection,
    val state: CallState,
    val startTimeMs: Long,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false
)

data class CallMetrics(
    val durationMs: Long,
    val jitterStats: JitterStats,
    val latencyMs: Long
)

class CallManager(
    private val context: Context,
    private val nearbyClient: NearbyMeshClient,
    private val localIdentity: LocalIdentity
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private val audioCapture = AudioCaptureManager(context)
    private val audioPlayback = AudioPlaybackManager(context)
    
    private val _activeCall = MutableStateFlow<ActiveCall?>(null)
    val activeCall: StateFlow<ActiveCall?> = _activeCall.asStateFlow()
    
    private val _incomingCall = MutableStateFlow<IncomingCallInfo?>(null)
    val incomingCall: StateFlow<IncomingCallInfo?> = _incomingCall.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()
    
    private val _callState = MutableStateFlow(CallState.IDLE)
    val callState: StateFlow<CallState> = _callState.asStateFlow()
    
    private var audioStreamJob: Job? = null
    private var callStartTime = 0L
    private var webrtcSession: WebRtcCallSession? = null
    
    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    
    fun initiateCall(peerId: String, peerName: String): Boolean {
        if (_activeCall.value != null) return false
        
        val callId = UUID.randomUUID().toString()
        
        val call = ActiveCall(
            callId = callId,
            peerId = peerId,
            peerName = peerName,
            direction = CallDirection.OUTGOING,
            state = CallState.OUTGOING_OFFER_SENT,
            startTimeMs = System.currentTimeMillis()
        )
        
        _activeCall.value = call
        _callState.value = CallState.OUTGOING_OFFER_SENT
        
        webrtcSession = WebRtcCallSession(
            context = context,
            callId = callId,
            isInitiator = true,
            onSendSignal = { phase, sdpOrIce ->
                nearbyClient.broadcast(localIdentity, OutboundContent.CallSignal(callId = callId, phase = phase, sdpOrIce = sdpOrIce))
            },
            onConnected = {
                callStartTime = System.currentTimeMillis()
                _activeCall.value = _activeCall.value?.copy(state = CallState.ACTIVE)
                _callState.value = CallState.ACTIVE
            },
            onDisconnected = { endCallInternal() },
            onRemoteVideoTrack = { track ->
                Handler(Looper.getMainLooper()).post { _remoteVideoTrack.value = track }
            }
        )
        webrtcSession?.createOffer()
        
        return true
    }
    
    fun handleIncomingSignal(callId: String, phase: String, peerId: String, peerName: String, sdpOrIce: String = "") {
        when (phase) {
            "offer" -> {
                if (_activeCall.value == null) {
                    _incomingCall.value = IncomingCallInfo(
                        callId = callId,
                        peerId = peerId,
                        peerName = peerName
                    )
                    _callState.value = CallState.INCOMING_OFFER_RECEIVED
                    if (sdpOrIce.startsWith("offer\n")) {
                        webrtcSession = WebRtcCallSession(
                            context = context,
                            callId = callId,
                            isInitiator = false,
                            onSendSignal = { p, payload ->
                                nearbyClient.broadcast(localIdentity, OutboundContent.CallSignal(callId = callId, phase = p, sdpOrIce = payload))
                            },
                            onConnected = {
                                callStartTime = System.currentTimeMillis()
                                _activeCall.value = _activeCall.value?.copy(state = CallState.ACTIVE)
                                _callState.value = CallState.ACTIVE
                            },
                            onDisconnected = { endCallInternal() },
                            onRemoteVideoTrack = { track ->
                                Handler(Looper.getMainLooper()).post { _remoteVideoTrack.value = track }
                            }
                        )
                        webrtcSession?.setRemoteOffer(sdpOrIce)
                    }
                }
            }
            "answer" -> {
                val current = _activeCall.value
                if (current?.callId == callId && current.direction == CallDirection.OUTGOING) {
                    if (sdpOrIce.startsWith("answer\n")) {
                        webrtcSession?.setRemoteAnswer(sdpOrIce)
                    }
                    _activeCall.value = current.copy(state = CallState.CONNECTING)
                    _callState.value = CallState.CONNECTING
                    startAudioStream()
                }
            }
            "ice" -> {
                if (sdpOrIce.startsWith("ice\n")) {
                    webrtcSession?.addIceCandidate(sdpOrIce)
                }
            }
            "end" -> {
                val current = _activeCall.value
                if (current?.callId == callId) {
                    endCallInternal()
                }
                if (_incomingCall.value?.callId == callId) {
                    _incomingCall.value = null
                    _callState.value = CallState.IDLE
                }
            }
        }
    }
    
    fun acceptCall() {
        val incoming = _incomingCall.value ?: return
        
        val call = ActiveCall(
            callId = incoming.callId,
            peerId = incoming.peerId,
            peerName = incoming.peerName,
            direction = CallDirection.INCOMING,
            state = CallState.CONNECTING,
            startTimeMs = System.currentTimeMillis()
        )
        
        _activeCall.value = call
        _incomingCall.value = null
        _callState.value = CallState.CONNECTING

        val signal = OutboundContent.CallSignal(
            callId = incoming.callId,
            phase = "answer",
            sdpOrIce = "ok"
        )
        nearbyClient.broadcast(localIdentity, signal)
        startAudioStream()
    }
    
    fun declineCall() {
        val incoming = _incomingCall.value ?: return
        
        val signal = OutboundContent.CallSignal(
            callId = incoming.callId,
            phase = "end",
            sdpOrIce = ""
        )
        nearbyClient.broadcast(localIdentity, signal)
        
        _incomingCall.value = null
        _callState.value = CallState.IDLE
    }
    
    fun endCall() {
        val current = _activeCall.value ?: return
        
        val signal = OutboundContent.CallSignal(
            callId = current.callId,
            phase = "end",
            sdpOrIce = ""
        )
        nearbyClient.broadcast(localIdentity, signal)
        
        endCallInternal()
    }
    
    private fun endCallInternal() {
        _remoteVideoTrack.value = null
        webrtcSession?.close()
        webrtcSession = null
        audioStreamJob?.cancel()
        audioStreamJob = null
        
        audioCapture.stop()
        audioPlayback.stop()
        
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
        
        _activeCall.value = null
        _callState.value = CallState.ENDED
        
        scope.launch {
            kotlinx.coroutines.delay(1000)
            _callState.value = CallState.IDLE
        }
    }
    
    private fun startAudioStream() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        
        callStartTime = System.currentTimeMillis()
        
        audioCapture.start()
        audioPlayback.start()
        
        _activeCall.value = _activeCall.value?.copy(state = CallState.ACTIVE)
        _callState.value = CallState.ACTIVE
        
        audioStreamJob = scope.launch(Dispatchers.IO) {
            audioCapture.audioFrames.collect { frame ->
                val current = _activeCall.value
                if (current != null && current.state == CallState.ACTIVE) {
                    val audioPacket = OutboundContent.AudioPacket(
                        callId = current.callId,
                        sequenceNumber = frame.sequenceNumber,
                        timestampMs = frame.timestampMs,
                        audioDataBase64 = frame.base64Data
                    )
                    nearbyClient.broadcast(localIdentity, audioPacket)
                }
            }
        }
    }
    
    fun handleAudioPacket(callId: String, sequenceNumber: Long, audioDataBase64: String) {
        val current = _activeCall.value
        if (current?.callId == callId && current.state == CallState.ACTIVE) {
            audioPlayback.enqueueBase64Frame(sequenceNumber, audioDataBase64)
        }
    }
    
    fun toggleMute(): Boolean {
        val current = _activeCall.value ?: return false
        val newMuted = !current.isMuted
        _activeCall.value = current.copy(isMuted = newMuted)
        audioCapture.setMuted(newMuted)
        return newMuted
    }
    
    fun toggleSpeaker(): Boolean {
        val current = _activeCall.value ?: return false
        val newSpeaker = !current.isSpeakerOn
        _activeCall.value = current.copy(isSpeakerOn = newSpeaker)
        audioPlayback.setSpeakerphoneOn(newSpeaker)
        return newSpeaker
    }
    
    fun getCallDuration(): Long {
        val current = _activeCall.value ?: return 0
        return if (current.state == CallState.ACTIVE) {
            System.currentTimeMillis() - callStartTime
        } else 0
    }
    
    fun getMetrics(): CallMetrics {
        return CallMetrics(
            durationMs = getCallDuration(),
            jitterStats = audioPlayback.getJitterStats(),
            latencyMs = 0
        )
    }
    
    fun release() {
        endCallInternal()
        scope.cancel()
    }
}

data class IncomingCallInfo(
    val callId: String,
    val peerId: String,
    val peerName: String
)
