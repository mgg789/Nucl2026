package com.peerdone.app.core.call

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.util.Log
import android.os.Looper
import android.util.Base64
import com.peerdone.app.core.audio.AudioCaptureManager
import com.peerdone.app.core.audio.AudioPlaybackManager
import com.peerdone.app.core.audio.JitterStats
import com.peerdone.app.core.message.OutboundContent
import com.peerdone.app.data.MeshClientRouter
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
import java.util.UUID

private const val SDP_CHUNK_SIZE = 8000
private const val SDP_CHUNK_THRESHOLD = 25_000
private const val CALL_LOG_TAG = "PeerDoneCall"

enum class CallDirection { OUTGOING, INCOMING }

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

/**
 * WebRTC звонки (голос + видео). SDP передаётся чанками из‑за лимита Nearby 32 KB.
 * Fallback: при отсутствии WebRTC-соединения голос идёт по mesh (AudioPacket).
 */
class CallManager(
    private val context: Context,
    private val meshClient: MeshClientRouter,
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

    private val offerChunks = mutableMapOf<String, SdpChunkAccumulator>()
    private val answerChunks = mutableMapOf<String, SdpChunkAccumulator>()
    /** Полный offer для входящего вызова (применяется при Accept). */
    private val pendingIncomingOffer = mutableMapOf<String, String>()

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private data class SdpChunkAccumulator(val total: Int, val chunks: MutableMap<Int, String>) {
        fun add(index: Int, content: String) { chunks[index] = content }
        fun isComplete(): Boolean = chunks.size == total
        fun reassemble(): String = chunks.toSortedMap().values.joinToString("")
    }

    /** Отправка сигнала звонка. targetPeerId: только этому пиру (1:1); иначе broadcast. */
    private fun sendCallSignal(callId: String, phase: String, sdpOrIce: String, targetPeerId: String?) {
        Log.d(CALL_LOG_TAG, "sendCallSignal phase=$phase callId=${callId.take(8)} targetPeerId=${targetPeerId?.take(20)}")
        val signal = { content: OutboundContent.CallSignal ->
            if (targetPeerId != null) meshClient.sendToPeer(targetPeerId, localIdentity, content)
            else meshClient.broadcast(localIdentity, content)
        }
        if ((phase == "offer" || phase == "answer") && sdpOrIce.length > SDP_CHUNK_THRESHOLD) {
            val parts = sdpOrIce.chunked(SDP_CHUNK_SIZE)
            signal(OutboundContent.CallSignal(callId = callId, phase = phase, sdpOrIce = "chunks|${parts.size}"))
            parts.forEachIndexed { i, chunk ->
                val b64 = Base64.encodeToString(chunk.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                signal(OutboundContent.CallSignal(callId = callId, phase = "${phase}_chunk", sdpOrIce = "$i|${parts.size}|$b64"))
            }
        } else {
            signal(OutboundContent.CallSignal(callId = callId, phase = phase, sdpOrIce = sdpOrIce))
        }
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
            onSendSignal = { phase, payload -> sendCallSignal(callId, phase, payload, peerId) },
            onConnected = {
                Handler(Looper.getMainLooper()).post {
                    callStartTime = System.currentTimeMillis()
                    _activeCall.value = _activeCall.value?.copy(state = CallState.ACTIVE)
                    _callState.value = CallState.ACTIVE
                    setupCallAudioMode()
                }
            },
            onDisconnected = { endCallInternal() },
            onRemoteVideoTrack = { track ->
                Handler(Looper.getMainLooper()).post { _remoteVideoTrack.value = track }
            }
        )
        webrtcSession?.createOffer()
        return true
    }

    private fun normPeer(s: String?) = s?.substringBefore("|")?.trim() ?: ""

    fun handleIncomingSignal(callId: String, phase: String, peerId: String, peerName: String, sdpOrIce: String = "") {
        when (phase) {
            "offer" -> {
                if (_activeCall.value == null) {
                    _incomingCall.value = IncomingCallInfo(callId = callId, peerId = peerId, peerName = peerName)
                    _callState.value = CallState.INCOMING_OFFER_RECEIVED
                }
                when {
                    sdpOrIce.startsWith("chunks|") -> {
                        val total = sdpOrIce.removePrefix("chunks|").trim().toIntOrNull() ?: return
                        offerChunks[callId] = SdpChunkAccumulator(total, mutableMapOf())
                    }
                    sdpOrIce.startsWith("offer\n") -> {
                        pendingIncomingOffer[callId] = sdpOrIce
                    }
                }
            }
            "offer_chunk" -> {
                val inc = _incomingCall.value
                if (inc?.callId != callId || normPeer(inc?.peerId) != normPeer(peerId)) return
                val (index, total, b64) = parseChunkPayload(sdpOrIce) ?: return
                val acc = offerChunks[callId] ?: return
                acc.add(index, runCatching { String(Base64.decode(b64, Base64.NO_WRAP), Charsets.UTF_8) }.getOrNull() ?: return)
                if (acc.isComplete()) {
                    offerChunks.remove(callId)
                    val fullOffer = "offer\n${acc.reassemble()}"
                    pendingIncomingOffer[callId] = fullOffer
                    if (webrtcSession != null && _activeCall.value?.callId == callId) {
                        webrtcSession?.setRemoteOffer(fullOffer)
                    }
                }
            }
            "answer" -> {
                val current = _activeCall.value
                Log.d(CALL_LOG_TAG, "handleAnswer callId=$callId peerId=$peerId current=${current?.callId?.take(8)} dir=${current?.direction} currentPeer=${current?.peerId?.take(20)}")
                if (current == null) {
                    Log.w(CALL_LOG_TAG, "handleAnswer skip: no activeCall")
                    return
                }
                if (current.callId != callId || current.direction != CallDirection.OUTGOING) {
                    Log.w(CALL_LOG_TAG, "handleAnswer skip: callId or direction mismatch")
                    return
                }
                if (normPeer(current.peerId) != normPeer(peerId)) {
                    Log.w(CALL_LOG_TAG, "handleAnswer skip: peerId mismatch normCurrent=${normPeer(current.peerId)} normPeer=${normPeer(peerId)}")
                    return
                }
                when {
                    sdpOrIce.startsWith("chunks|") -> {
                        val total = sdpOrIce.removePrefix("chunks|").trim().toIntOrNull() ?: return
                        answerChunks.getOrPut(callId) { SdpChunkAccumulator(total, mutableMapOf()) }
                        _activeCall.value = current.copy(state = CallState.CONNECTING)
                        _callState.value = CallState.CONNECTING
                    }
                    sdpOrIce.startsWith("answer\n") -> {
                        Log.d(CALL_LOG_TAG, "handleAnswer applying setRemoteAnswer len=${sdpOrIce.length}")
                        _activeCall.value = current.copy(state = CallState.CONNECTING)
                        _callState.value = CallState.CONNECTING
                        webrtcSession?.setRemoteAnswer(sdpOrIce)
                    }
                }
            }
            "answer_chunk" -> {
                val current = _activeCall.value ?: return
                if (current.callId != callId || normPeer(current.peerId) != normPeer(peerId)) return
                val (index, total, b64) = parseChunkPayload(sdpOrIce) ?: return
                val acc = answerChunks.getOrPut(callId) { SdpChunkAccumulator(total, mutableMapOf()) }
                if (acc.chunks.size >= total && index !in acc.chunks) {
                    Log.w(CALL_LOG_TAG, "answer_chunk duplicate or wrong total? index=$index total=$total")
                }
                acc.add(index, runCatching { String(Base64.decode(b64, Base64.NO_WRAP), Charsets.UTF_8) }.getOrNull() ?: return)
                if (acc.isComplete()) {
                    answerChunks.remove(callId)
                    _activeCall.value = current.copy(state = CallState.CONNECTING)
                    _callState.value = CallState.CONNECTING
                    webrtcSession?.setRemoteAnswer("answer\n${acc.reassemble()}")
                }
            }
            "ice" -> {
                val current = _activeCall.value
                if (current != null && current.callId == callId && normPeer(current.peerId) == normPeer(peerId) && sdpOrIce.startsWith("ice\n")) {
                    webrtcSession?.addIceCandidate(sdpOrIce)
                }
            }
            "end" -> {
                offerChunks.remove(callId)
                answerChunks.remove(callId)
                val current = _activeCall.value
                val incoming = _incomingCall.value
                val fromOurPeer = normPeer(current?.peerId) == normPeer(peerId) || normPeer(incoming?.peerId) == normPeer(peerId)
                if (fromOurPeer && current?.callId == callId) endCallInternal()
                if (fromOurPeer && incoming?.callId == callId) _incomingCall.value = null
                if (fromOurPeer && (current?.callId == callId || incoming?.callId == callId)) _callState.value = CallState.IDLE
            }
        }
    }

    private fun parseChunkPayload(sdpOrIce: String): Triple<Int, Int, String>? {
        val parts = sdpOrIce.split("|", limit = 3)
        if (parts.size != 3) return null
        val i = parts[0].toIntOrNull() ?: return null
        val t = parts[1].toIntOrNull() ?: return null
        return Triple(i, t, parts[2])
    }

    private fun ensureWebRtcSessionCallee(callId: String, peerId: String, peerName: String) {
        if (webrtcSession != null) return
        webrtcSession = WebRtcCallSession(
            context = context,
            callId = callId,
            isInitiator = false,
            onSendSignal = { phase, payload -> sendCallSignal(callId, phase, payload, peerId) },
            onConnected = {
                Handler(Looper.getMainLooper()).post {
                    callStartTime = System.currentTimeMillis()
                    _activeCall.value = _activeCall.value?.copy(state = CallState.ACTIVE)
                    _callState.value = CallState.ACTIVE
                    setupCallAudioMode()
                }
            },
            onDisconnected = { endCallInternal() },
            onRemoteVideoTrack = { track ->
                Handler(Looper.getMainLooper()).post { _remoteVideoTrack.value = track }
            }
        )
    }

    fun acceptCall() {
        val incoming = _incomingCall.value ?: return
        val fullOffer = pendingIncomingOffer.remove(incoming.callId)
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

        ensureWebRtcSessionCallee(incoming.callId, incoming.peerId, incoming.peerName)
        if (!fullOffer.isNullOrEmpty()) webrtcSession?.setRemoteOffer(fullOffer)
        // Реальный SDP answer уйдёт из WebRtcCallSession после createAnswer(). ACTIVE и таймер — только по onConnected (ICE).
    }

    fun declineCall() {
        val incoming = _incomingCall.value ?: return
        offerChunks.remove(incoming.callId)
        pendingIncomingOffer.remove(incoming.callId)
        sendCallSignal(incoming.callId, "end", "", incoming.peerId)
        _incomingCall.value = null
        _callState.value = CallState.IDLE
    }

    fun endCall() {
        val current = _activeCall.value ?: return
        val peerId = current.peerId
        offerChunks.remove(current.callId)
        answerChunks.remove(current.callId)
        sendCallSignal(current.callId, "end", "", peerId)
        scope.launch {
            kotlinx.coroutines.delay(120)
            sendCallSignal(current.callId, "end", "", peerId)
        }
        endCallInternal()
    }

    private fun endCallInternal() {
        _remoteVideoTrack.value = null
        pendingIncomingOffer.clear()
        webrtcSession?.close()
        webrtcSession = null
        audioStreamJob?.cancel()
        audioStreamJob = null
        audioCapture.stop()
        audioPlayback.stop()
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
        _activeCall.value = null
        _callState.value = CallState.IDLE
    }

    /** Режим аудио для звонка (WebRTC сам передаёт медиа, mesh-fallback — отдельно). */
    private fun setupCallAudioMode() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audioManager.requestAudioFocus({ _ -> }, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
    }

    /** Fallback: голос через mesh (AudioPacket), если WebRTC не соединился. */
    private fun startAudioStreamFallback() {
        audioStreamJob?.cancel()
        audioStreamJob = null
        setupCallAudioMode()
        callStartTime = System.currentTimeMillis()
        audioCapture.start()
        audioPlayback.start()
        _activeCall.value = _activeCall.value?.copy(state = CallState.ACTIVE)
        _callState.value = CallState.ACTIVE
        val targetPeerId = _activeCall.value?.peerId
        audioStreamJob = scope.launch(Dispatchers.IO) {
            audioCapture.audioFrames.collect { frame ->
                val c = _activeCall.value
                val peerId = c?.peerId ?: targetPeerId
                if (c != null && c.state == CallState.ACTIVE && peerId != null) {
                    meshClient.sendToPeer(
                        peerId,
                        localIdentity,
                        OutboundContent.AudioPacket(callId = c.callId, sequenceNumber = frame.sequenceNumber, timestampMs = frame.timestampMs, audioDataBase64 = frame.base64Data)
                    )
                }
            }
        }
    }

    fun setLocalVideoSink(sink: org.webrtc.VideoSink?) {
        webrtcSession?.setLocalVideoSink(sink)
    }

    fun handleAudioPacket(callId: String, sequenceNumber: Long, audioDataBase64: String, senderPeerId: String? = null) {
        val c = _activeCall.value ?: return
        if (c.callId != callId || c.state != CallState.ACTIVE) return
        if (senderPeerId != null && normPeer(c.peerId) != normPeer(senderPeerId)) return
        audioPlayback.enqueueBase64Frame(sequenceNumber, audioDataBase64)
    }

    fun toggleMute(): Boolean {
        val c = _activeCall.value ?: return false
        val v = !c.isMuted
        _activeCall.value = c.copy(isMuted = v)
        if (webrtcSession != null) webrtcSession.setMuted(v) else audioCapture.setMuted(v)
        return v
    }

    fun toggleSpeaker(): Boolean {
        val c = _activeCall.value ?: return false
        val v = !c.isSpeakerOn
        _activeCall.value = c.copy(isSpeakerOn = v)
        audioPlayback.setSpeakerphoneOn(v)
        return v
    }

    fun getCallDuration(): Long {
        val c = _activeCall.value ?: return 0
        return if (c.state == CallState.ACTIVE) System.currentTimeMillis() - callStartTime else 0
    }

    fun getMetrics(): CallMetrics = CallMetrics(getCallDuration(), audioPlayback.getJitterStats(), 0)
    fun release() { endCallInternal(); scope.cancel() }
}

data class IncomingCallInfo(val callId: String, val peerId: String, val peerName: String)
