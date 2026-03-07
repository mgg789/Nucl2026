package com.peerdone.app.core.call

import android.content.Context
import android.util.Log
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.Camera1Capturer
import org.webrtc.Camera1Enumerator
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.VideoSink
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * WebRTC-сессия звонка (голос + видео): сигналинг (SDP/ICE) идёт через mesh,
 * медиа — напрямую через PeerConnection (как в Meshenger).
 */
class WebRtcCallSession(
    private val context: Context,
    private val callId: String,
    private val isInitiator: Boolean,
    private val onSendSignal: (phase: String, sdpOrIce: String) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onRemoteVideoTrack: ((VideoTrack?) -> Unit)? = null,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var cameraCapturer: org.webrtc.VideoCapturer? = null
    private var localVideoSink: VideoSink? = null
    private val tag = "WebRtcCallSession"
    /** ICE кандидаты приходят до setRemoteDescription; по спецификации добавлять их нужно после. */
    private val pendingIceCandidates = CopyOnWriteArrayList<String>()
    private var remoteDescriptionSet = false

    init {
        executor.execute {
            try {
                val options = PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(true)
                    .createInitializationOptions()
                PeerConnectionFactory.initialize(options)
                peerConnectionFactory = PeerConnectionFactory.builder().createPeerConnectionFactory()
            } catch (e: Exception) {
                Log.e(tag, "PeerConnectionFactory init failed", e)
            }
        }
    }

    fun createOffer() {
        executor.execute {
            ensurePeerConnection()
            val sdpConstraints = MediaConstraints()
            peerConnection?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    desc ?: return
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            val payload = "offer\n${desc.description}"
                            onSendSignal("offer", payload)
                        }
                        override fun onCreateFailure(error: String?) {}
                        override fun onSetFailure(error: String?) {}
                    }, desc)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) { Log.e(tag, "createOffer failed: $error") }
                override fun onSetFailure(error: String?) {}
            }, sdpConstraints)
        }
    }

    fun setRemoteOffer(sdpPayload: String) {
        executor.execute {
            ensurePeerConnection()
            val sdp = sdpPayload.removePrefix("offer\n").trim()
            val desc = SessionDescription(SessionDescription.Type.OFFER, sdp)
            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onSetSuccess() {
                    remoteDescriptionSet = true
                    flushPendingIceCandidates()
                    createAnswer()
                }
                override fun onCreateFailure(error: String?) {}
                override fun onSetFailure(error: String?) { Log.e(tag, "setRemoteOffer failed: $error") }
            }, desc)
        }
    }

    /**
     * Локальная сеть (Nearby/Wi-Fi Direct): пустой iceServers — WebRTC собирает только host-кандидаты,
     * соединение P2P без интернета и STUN/TURN. Для LAN/Wi-Fi Direct host-кандидатов достаточно.
     */
    private fun ensurePeerConnection() {
        if (peerConnection != null) return
        val factory = peerConnectionFactory ?: return
        val iceServers = emptyList<org.webrtc.PeerConnection.IceServer>()
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val constraints = MediaConstraints()
        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                if (state == PeerConnection.IceConnectionState.CONNECTED) {
                    onConnected()
                } else if (state == PeerConnection.IceConnectionState.DISCONNECTED ||
                    state == PeerConnection.IceConnectionState.FAILED ||
                    state == PeerConnection.IceConnectionState.CLOSED
                ) {
                    onDisconnected()
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let { sendIce(it) }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: org.webrtc.MediaStream?) {}
            override fun onRemoveStream(stream: org.webrtc.MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out org.webrtc.MediaStream>?) {
                val track = receiver?.track()
                if (track is VideoTrack) {
                    executor.execute { onRemoteVideoTrack?.invoke(track) }
                }
            }
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {}
            override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent?) {}
        }) ?: return
        localAudioSource = factory.createAudioSource(MediaConstraints())
        localAudioTrack = factory.createAudioTrack("audio0", localAudioSource)
        localAudioTrack?.setEnabled(true)
        peerConnection?.addTrack(localAudioTrack, listOf("stream0"))

        try {
            val enumerator = Camera1Enumerator(false)
            val deviceNames = enumerator.deviceNames
            val front = enumerator.deviceNames.find { enumerator.isFrontFacing(it) } ?: deviceNames.firstOrNull()
            if (front != null) {
                cameraCapturer = Camera1Capturer(front, object : org.webrtc.CameraVideoCapturer.CameraEventsHandler {
                    override fun onCameraError(error: String?) { Log.e(tag, "Camera error: $error") }
                    override fun onCameraDisconnected() {}
                    override fun onCameraFreezed(error: String?) {}
                    override fun onCameraOpening(cameraName: String?) {}
                    override fun onFirstFrameAvailable() {}
                    override fun onCameraClosed() {}
                }, true)
                localVideoSource = factory.createVideoSource(false)
                val eglBase = org.webrtc.EglBase.create()
                val surfaceHelper = org.webrtc.SurfaceTextureHelper.create("Capture", eglBase.eglBaseContext)
                cameraCapturer?.initialize(surfaceHelper, context, localVideoSource?.capturerObserver)
                cameraCapturer?.startCapture(1280, 720, 30)
                localVideoTrack = factory.createVideoTrack("video0", localVideoSource)
                localVideoTrack?.setEnabled(true)
                peerConnection?.addTrack(localVideoTrack, listOf("stream0"))
                localVideoSink?.let { localVideoTrack?.addSink(it) }
            }
        } catch (e: Exception) {
            Log.e(tag, "Video track init failed", e)
        }
    }

    fun setLocalVideoSink(sink: VideoSink?) {
        localVideoSink = sink
        executor.execute { localVideoTrack?.addSink(sink) }
    }

    fun setMuted(muted: Boolean) {
        executor.execute { localAudioTrack?.setEnabled(!muted) }
    }

    private fun createAnswer() {
        val constraints = MediaConstraints()
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc ?: return
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        val payload = "answer\n${desc.description}"
                        onSendSignal("answer", payload)
                    }
                    override fun onCreateFailure(error: String?) {}
                    override fun onSetFailure(error: String?) {}
                }, desc)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) { Log.e(tag, "createAnswer failed: $error") }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    fun setRemoteAnswer(sdpPayload: String) {
        executor.execute {
            val sdp = sdpPayload.removePrefix("answer\n").trim()
            val desc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onSetSuccess() {
                    remoteDescriptionSet = true
                    flushPendingIceCandidates()
                    // onConnected() вызовется из onIceConnectionChange(CONNECTED)
                }
                override fun onCreateFailure(error: String?) {}
                override fun onSetFailure(error: String?) { Log.e(tag, "setRemoteAnswer failed: $error") }
            }, desc)
        }
    }

    fun addIceCandidate(payload: String) {
        executor.execute {
            if (!remoteDescriptionSet) {
                pendingIceCandidates.add(payload)
                return@execute
            }
            addIceCandidateInternal(payload)
        }
    }

    private fun flushPendingIceCandidates() {
        pendingIceCandidates.forEach { addIceCandidateInternal(it) }
        pendingIceCandidates.clear()
    }

    private fun addIceCandidateInternal(payload: String) {
        val rest = payload.removePrefix("ice\n") ?: return
        val firstNl = rest.indexOf('\n')
        val secondNl = if (firstNl >= 0) rest.indexOf('\n', firstNl + 1) else -1
        if (firstNl < 0 || secondNl < 0) return
        val sdpMid = rest.substring(0, firstNl)
        val sdpMLineIndex = rest.substring(firstNl + 1, secondNl).toIntOrNull() ?: 0
        val candidate = rest.substring(secondNl + 1)
        val ice = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        peerConnection?.addIceCandidate(ice, object : org.webrtc.AddIceObserver {
            override fun onAddSuccess() { Log.d(tag, "addIceCandidate success") }
            override fun onAddFailure(error: String?) { Log.e(tag, "addIceCandidate failed: $error") }
        })
    }

    private fun sendIce(candidate: IceCandidate) {
        val payload = "ice\n${candidate.sdpMid}\n${candidate.sdpMLineIndex}\n${candidate.sdp}"
        onSendSignal("ice", payload)
    }

    fun close() {
        executor.execute {
            cameraCapturer?.stopCapture()
            cameraCapturer?.dispose()
            cameraCapturer = null
            localVideoTrack?.dispose()
            localVideoSource?.dispose()
            localAudioTrack?.dispose()
            localAudioSource?.dispose()
            peerConnection?.close()
            peerConnection = null
            peerConnectionFactory?.dispose()
            peerConnectionFactory = null
        }
    }
}
