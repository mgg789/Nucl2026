import Foundation
import AVFoundation
import WebRTC

final class WebRTCCallEngine: NSObject {
    var onSignal: ((_ peerId: String, _ callId: String, _ phase: String, _ sdpOrIce: String) -> Void)?
    var onConnected: ((_ callId: String) -> Void)?
    var onDisconnected: ((_ callId: String) -> Void)?
    var onVideoTracksChanged: ((_ local: RTCVideoTrack?, _ remote: RTCVideoTrack?) -> Void)?

    private struct SessionContext {
        let callId: String
        let peerId: String
        var isVideo: Bool
    }

    private let queue = DispatchQueue(label: "com.peer.mesh.webrtc.call")
    private let factory: RTCPeerConnectionFactory

    private var context: SessionContext?
    private var peerConnection: RTCPeerConnection?
    private var localAudioSource: RTCAudioSource?
    private var localAudioTrack: RTCAudioTrack?
    private var localVideoSource: RTCVideoSource?
    private var localVideoTrack: RTCVideoTrack?
    private var localVideoSender: RTCRtpSender?
    private var localVideoCapturer: RTCCameraVideoCapturer?
    private var remoteVideoTrack: RTCVideoTrack?
    private var isCapturingVideo = false
    private var hasRemoteDescription = false
    private var pendingRemoteIcePayloads: [String] = []
    private var isMakingOffer = false
    private var isProcessingRemoteOffer = false
    private var pendingOfferReceiveVideo: Bool?
    private var isMicrophoneMuted = false
    private var isSpeakerEnabled = true

    private var didNotifyConnected = false
    private var isClosing = false
    private var sslInitialized = false

    override init() {
        RTCInitializeSSL()
        sslInitialized = true
        factory = RTCPeerConnectionFactory(
            encoderFactory: RTCDefaultVideoEncoderFactory(),
            decoderFactory: RTCDefaultVideoDecoderFactory()
        )
        super.init()
    }

    deinit {
        close()
        if sslInitialized {
            RTCCleanupSSL()
            sslInitialized = false
        }
    }

    func startOutgoing(callId: String, peerId: String, isVideo: Bool) {
        queue.async { [weak self] in
            guard let self else { return }
            guard self.prepareSession(callId: callId, peerId: peerId, isVideo: isVideo) else { return }
            guard let connection = self.peerConnection else { return }
            self.ensureLocalVideo(connection: connection, enabled: isVideo)
            self.requestOffer(for: callId, receiveVideo: isVideo)
        }
    }

    func acceptIncoming(callId: String, peerId: String, isVideo: Bool, remoteOfferPayload: String) {
        queue.async { [weak self] in
            guard let self else { return }
            guard self.prepareSession(callId: callId, peerId: peerId, isVideo: isVideo) else { return }
            guard let connection = self.peerConnection else { return }

            let offerBody: String
            if remoteOfferPayload.hasPrefix("offer\n") {
                offerBody = String(remoteOfferPayload.dropFirst("offer\n".count))
            } else {
                offerBody = remoteOfferPayload
            }
            let offeredVideo = offerBody.contains("m=video")
            self.ensureLocalVideo(connection: connection, enabled: isVideo)

            let offer = RTCSessionDescription(type: .offer, sdp: offerBody)
            connection.setRemoteDescription(offer) { [weak self] error in
                guard let self, error == nil else { return }
                self.hasRemoteDescription = true
                self.flushPendingRemoteIceIfPossible()
                self.scheduleRemoteVideoTrackRefresh()

                let answerConstraints = self.mediaConstraints(receiveVideo: offeredVideo || isVideo)

                connection.answer(for: answerConstraints) { [weak self] sdp, answerError in
                    guard let self, let sdp, answerError == nil else { return }
                    connection.setLocalDescription(sdp) { [weak self] setError in
                        guard let self, setError == nil, let ctx = self.context, ctx.callId == callId else { return }
                        self.onSignal?(ctx.peerId, callId, "answer", "answer\n\(sdp.sdp)")
                    }
                }
            }
        }
    }

    func handleRemoteOffer(callId: String, payload: String) {
        queue.async { [weak self] in
            guard let self, let ctx = self.context, ctx.callId == callId else { return }
            guard let connection = self.peerConnection else { return }

            let offerBody: String
            if payload.hasPrefix("offer\n") {
                offerBody = String(payload.dropFirst("offer\n".count))
            } else {
                offerBody = payload
            }
            let offeredVideo = offerBody.contains("m=video")
            if offeredVideo, var updated = self.context {
                updated.isVideo = true
                self.context = updated
            }
            let shouldSendVideo = self.context?.isVideo ?? false
            self.ensureLocalVideo(connection: connection, enabled: shouldSendVideo)

            self.isProcessingRemoteOffer = true

            let applyRemoteOffer: () -> Void = { [weak self] in
                guard let self else { return }
                let offer = RTCSessionDescription(type: .offer, sdp: offerBody)
                connection.setRemoteDescription(offer) { [weak self] error in
                    guard let self else { return }
                    guard error == nil else {
                        self.isProcessingRemoteOffer = false
                        self.drainPendingOffer(for: callId)
                        return
                    }
                    self.hasRemoteDescription = true
                    self.flushPendingRemoteIceIfPossible()
                    self.scheduleRemoteVideoTrackRefresh()
                    let answerConstraints = self.mediaConstraints(receiveVideo: offeredVideo || shouldSendVideo)
                    connection.answer(for: answerConstraints) { [weak self] sdp, answerError in
                        guard let self else { return }
                        guard let sdp, answerError == nil else {
                            self.isProcessingRemoteOffer = false
                            self.drainPendingOffer(for: callId)
                            return
                        }
                        connection.setLocalDescription(sdp) { [weak self] setError in
                            guard let self else { return }
                            self.isProcessingRemoteOffer = false
                            if setError == nil, let current = self.context, current.callId == callId {
                                self.onSignal?(current.peerId, callId, "answer", "answer\n\(sdp.sdp)")
                            }
                            self.drainPendingOffer(for: callId)
                        }
                    }
                }
            }

            if connection.signalingState == .haveLocalOffer {
                let rollback = RTCSessionDescription(type: .rollback, sdp: "")
                connection.setLocalDescription(rollback) { [weak self] rollbackError in
                    guard let self else { return }
                    guard rollbackError == nil else {
                        self.isProcessingRemoteOffer = false
                        self.drainPendingOffer(for: callId)
                        return
                    }
                    applyRemoteOffer()
                }
            } else {
                applyRemoteOffer()
            }
        }
    }

    func handleRemoteAnswer(callId: String, payload: String) {
        queue.async { [weak self] in
            guard let self, let ctx = self.context, ctx.callId == callId else { return }
            guard let connection = self.peerConnection else { return }

            let body: String
            if payload.hasPrefix("answer\n") {
                body = String(payload.dropFirst("answer\n".count))
            } else {
                body = payload
            }

            let answer = RTCSessionDescription(type: .answer, sdp: body)
            connection.setRemoteDescription(answer) { [weak self] _ in
                self?.hasRemoteDescription = true
                self?.flushPendingRemoteIceIfPossible()
                self?.scheduleRemoteVideoTrackRefresh()
            }
        }
    }

    func handleRemoteIce(callId: String, payload: String) {
        queue.async { [weak self] in
            guard let self, let ctx = self.context, ctx.callId == callId else { return }
            guard let connection = self.peerConnection else { return }

            let body: String
            if payload.hasPrefix("ice\n") {
                body = String(payload.dropFirst("ice\n".count))
            } else {
                body = payload
            }

            guard self.hasRemoteDescription else {
                self.pendingRemoteIcePayloads.append(body)
                return
            }

            self.addIceCandidate(fromBody: body)
        }
    }

    private func addIceCandidate(fromBody body: String) {
        guard let connection = peerConnection else { return }

        let parts = body.split(separator: "\n", maxSplits: 2, omittingEmptySubsequences: false)
        guard parts.count == 3 else { return }

        let sdpMid = String(parts[0])
        let mLine = Int32(parts[1]) ?? 0
        let candidateValue = String(parts[2])
        let candidate = RTCIceCandidate(sdp: candidateValue, sdpMLineIndex: mLine, sdpMid: sdpMid)
        connection.add(candidate)
    }

    private func flushPendingRemoteIceIfPossible() {
        guard hasRemoteDescription else { return }
        guard !pendingRemoteIcePayloads.isEmpty else { return }
        let payloads = pendingRemoteIcePayloads
        pendingRemoteIcePayloads.removeAll()
        for payload in payloads {
            addIceCandidate(fromBody: payload)
        }
    }

    func close(callId: String? = nil) {
        queue.async { [weak self] in
            guard let self else { return }
            if let callId, self.context?.callId != callId {
                return
            }
            self.teardown(notifyDisconnect: false)
        }
    }

    func setVideoEnabled(callId: String, enabled: Bool) {
        queue.async { [weak self] in
            guard let self, var ctx = self.context, ctx.callId == callId else { return }
            guard let connection = self.peerConnection else { return }
            ctx.isVideo = enabled
            self.context = ctx
            self.ensureLocalVideo(connection: connection, enabled: enabled)
            self.requestOffer(for: callId, receiveVideo: enabled || self.remoteVideoTrack != nil)
        }
    }

    func setMicrophoneMuted(_ muted: Bool) {
        queue.async { [weak self] in
            guard let self else { return }
            self.isMicrophoneMuted = muted
            self.localAudioTrack?.isEnabled = !muted
        }
    }

    func setSpeakerEnabled(_ enabled: Bool) {
        queue.async { [weak self] in
            guard let self else { return }
            self.isSpeakerEnabled = enabled
            self.applyAudioRoute()
        }
    }

    private func prepareSession(callId: String, peerId: String, isVideo: Bool) -> Bool {
        if let ctx = context, ctx.callId != callId {
            teardown(notifyDisconnect: false)
        }

        if peerConnection != nil {
            return true
        }

        configureAudioSession()

        let config = RTCConfiguration()
        config.sdpSemantics = .unifiedPlan
        config.continualGatheringPolicy = .gatherContinually
        config.iceTransportPolicy = .all
        config.iceServers = [
            RTCIceServer(urlStrings: ["stun:stun.l.google.com:19302"])
        ]

        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        guard let connection = factory.peerConnection(with: config, constraints: constraints, delegate: self) else {
            return false
        }

        context = SessionContext(callId: callId, peerId: peerId, isVideo: isVideo)
        didNotifyConnected = false
        isClosing = false
        hasRemoteDescription = false
        pendingRemoteIcePayloads.removeAll()
        isMakingOffer = false
        isProcessingRemoteOffer = false
        pendingOfferReceiveVideo = nil
        peerConnection = connection

        let audioSource = factory.audioSource(with: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil))
        localAudioSource = audioSource
        let audioTrack = factory.audioTrack(with: audioSource, trackId: "audio0")
        localAudioTrack = audioTrack
        audioTrack.isEnabled = !isMicrophoneMuted
        _ = connection.add(audioTrack, streamIds: ["stream0"])
        ensureLocalVideo(connection: connection, enabled: isVideo)
        applyAudioRoute()

        return true
    }

    private func teardown(notifyDisconnect: Bool) {
        if isClosing {
            return
        }
        isClosing = true
        let previousCallId = context?.callId

        peerConnection?.close()
        peerConnection = nil
        localAudioTrack = nil
        localAudioSource = nil
        stopLocalVideoCapture()
        localVideoSender = nil
        localVideoTrack = nil
        localVideoSource = nil
        localVideoCapturer = nil
        remoteVideoTrack = nil
        emitVideoTracksChanged()
        context = nil
        hasRemoteDescription = false
        pendingRemoteIcePayloads.removeAll()
        isMakingOffer = false
        isProcessingRemoteOffer = false
        pendingOfferReceiveVideo = nil
        isMicrophoneMuted = false
        isSpeakerEnabled = true
        didNotifyConnected = false
        isClosing = false

        deactivateAudioSession()

        if notifyDisconnect, let callId = previousCallId {
            onDisconnected?(callId)
        }
    }

    private func requestOffer(for callId: String, receiveVideo: Bool) {
        guard let ctx = context, ctx.callId == callId else { return }
        if isMakingOffer || isProcessingRemoteOffer {
            let previous = pendingOfferReceiveVideo ?? false
            pendingOfferReceiveVideo = previous || receiveVideo
            return
        }
        sendOfferNow(for: callId, receiveVideo: receiveVideo)
    }

    private func sendOfferNow(for callId: String, receiveVideo: Bool) {
        guard let connection = peerConnection else { return }
        isMakingOffer = true

        let constraints = mediaConstraints(receiveVideo: receiveVideo)
        connection.offer(for: constraints) { [weak self] sdp, error in
            guard let self else { return }
            guard let sdp, error == nil else {
                self.isMakingOffer = false
                self.drainPendingOffer(for: callId)
                return
            }

            connection.setLocalDescription(sdp) { [weak self] setError in
                guard let self else { return }
                self.isMakingOffer = false
                if setError == nil, let ctx = self.context, ctx.callId == callId {
                    self.onSignal?(ctx.peerId, callId, "offer", "offer\n\(sdp.sdp)")
                }
                self.drainPendingOffer(for: callId)
            }
        }
    }

    private func drainPendingOffer(for callId: String) {
        guard let pending = pendingOfferReceiveVideo else { return }
        pendingOfferReceiveVideo = nil
        requestOffer(for: callId, receiveVideo: pending)
    }

    private func mediaConstraints(receiveVideo: Bool) -> RTCMediaConstraints {
        RTCMediaConstraints(
            mandatoryConstraints: [
                "OfferToReceiveAudio": "true",
                "OfferToReceiveVideo": receiveVideo ? "true" : "false"
            ],
            optionalConstraints: nil
        )
    }

    private func ensureLocalVideo(connection: RTCPeerConnection, enabled: Bool) {
        if !enabled && localVideoTrack == nil {
            emitVideoTracksChanged()
            return
        }

        if localVideoTrack == nil {
            let source = factory.videoSource()
            localVideoSource = source
            let track = factory.videoTrack(with: source, trackId: "video0")
            localVideoTrack = track
            localVideoCapturer = RTCCameraVideoCapturer(delegate: source)
        }

        if let track = localVideoTrack, localVideoSender == nil {
            localVideoSender = connection.add(track, streamIds: ["stream0"])
        }

        localVideoTrack?.isEnabled = enabled
        if enabled {
            startLocalVideoCaptureIfNeeded()
        } else {
            stopLocalVideoCapture()
        }
        emitVideoTracksChanged()
    }

    private func startLocalVideoCaptureIfNeeded() {
        guard !isCapturingVideo else { return }
        guard let capturer = localVideoCapturer else { return }
        guard let device = preferredCaptureDevice() else { return }
        guard let format = preferredCaptureFormat(for: device) else { return }

        let fps = preferredFps(for: format)
        capturer.startCapture(with: device, format: format, fps: fps)
        isCapturingVideo = true
    }

    private func stopLocalVideoCapture() {
        guard isCapturingVideo else { return }
        localVideoCapturer?.stopCapture()
        isCapturingVideo = false
    }

    private func preferredCaptureDevice() -> AVCaptureDevice? {
        let devices = RTCCameraVideoCapturer.captureDevices()
        return devices.first(where: { $0.position == .front }) ?? devices.first
    }

    private func preferredCaptureFormat(for device: AVCaptureDevice) -> AVCaptureDevice.Format? {
        let formats = RTCCameraVideoCapturer.supportedFormats(for: device)
        guard !formats.isEmpty else { return nil }

        return formats.max { lhs, rhs in
            let lhsDim = CMVideoFormatDescriptionGetDimensions(lhs.formatDescription)
            let rhsDim = CMVideoFormatDescriptionGetDimensions(rhs.formatDescription)
            let lhsPixels = Int(lhsDim.width) * Int(lhsDim.height)
            let rhsPixels = Int(rhsDim.width) * Int(rhsDim.height)
            if lhsPixels == rhsPixels {
                return preferredFps(for: lhs) < preferredFps(for: rhs)
            }
            return lhsPixels < rhsPixels
        }
    }

    private func preferredFps(for format: AVCaptureDevice.Format) -> Int {
        let maxFps = format.videoSupportedFrameRateRanges
            .map(\.maxFrameRate)
            .max() ?? 24
        return Int(min(24, maxFps))
    }

    private func emitVideoTracksChanged() {
        let local = (localVideoTrack?.isEnabled == true) ? localVideoTrack : nil
        let remote = remoteVideoTrack
        DispatchQueue.main.async { [weak self] in
            self?.onVideoTracksChanged?(local, remote)
        }
    }

    private func scheduleRemoteVideoTrackRefresh() {
        refreshRemoteVideoTrack()
        queue.asyncAfter(deadline: .now() + 0.2) { [weak self] in
            self?.refreshRemoteVideoTrack()
        }
        queue.asyncAfter(deadline: .now() + 0.8) { [weak self] in
            self?.refreshRemoteVideoTrack()
        }
    }

    private func refreshRemoteVideoTrack() {
        guard let connection = peerConnection else {
            setRemoteVideoTrack(nil)
            return
        }

        let fromTransceivers = connection.transceivers
            .compactMap { $0.receiver.track as? RTCVideoTrack }
            .first
        let fromReceivers = connection.receivers
            .compactMap { $0.track as? RTCVideoTrack }
            .first

        let selected = fromTransceivers ?? fromReceivers
        setRemoteVideoTrack(selected)
    }

    private func setRemoteVideoTrack(_ track: RTCVideoTrack?) {
        let oldId = remoteVideoTrack?.trackId
        let newId = track?.trackId
        if oldId == newId {
            return
        }
        remoteVideoTrack = track
        emitVideoTracksChanged()
    }

    private func configureAudioSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(
            .playAndRecord,
            mode: .voiceChat,
            options: [.allowBluetoothHFP, .allowBluetoothA2DP]
        )
        try? session.setActive(true)
        applyAudioRoute()
    }

    private func deactivateAudioSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func applyAudioRoute() {
        let session = AVAudioSession.sharedInstance()
        try? session.overrideOutputAudioPort(isSpeakerEnabled ? .speaker : .none)
    }
}

extension WebRTCCallEngine: RTCPeerConnectionDelegate {
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}

    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {
        if let track = stream.videoTracks.first {
            setRemoteVideoTrack(track)
        }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {
        if stream.videoTracks.contains(where: { track in
            guard let remoteVideoTrack else { return false }
            return track == remoteVideoTrack || track.trackId == remoteVideoTrack.trackId
        }) {
            setRemoteVideoTrack(nil)
        }
    }

    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        guard let callId = context?.callId else { return }
        switch newState {
        case .connected, .completed:
            refreshRemoteVideoTrack()
            if !didNotifyConnected {
                didNotifyConnected = true
                onConnected?(callId)
            }
        case .disconnected, .failed, .closed:
            teardown(notifyDisconnect: true)
        default:
            break
        }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {
        if newState == .complete {
            refreshRemoteVideoTrack()
        }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        guard let ctx = context else { return }
        let mid = candidate.sdpMid ?? "audio"
        let payload = "ice\n\(mid)\n\(candidate.sdpMLineIndex)\n\(candidate.sdp)"
        onSignal?(ctx.peerId, ctx.callId, "ice", payload)
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}

    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCPeerConnectionState) {
        if stateChanged == .failed || stateChanged == .closed || stateChanged == .disconnected {
            teardown(notifyDisconnect: true)
        }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didStartReceivingOn transceiver: RTCRtpTransceiver) {
        if let track = transceiver.receiver.track as? RTCVideoTrack {
            setRemoteVideoTrack(track)
        } else {
            refreshRemoteVideoTrack()
        }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd rtpReceiver: RTCRtpReceiver, streams: [RTCMediaStream]) {
        if let track = rtpReceiver.track as? RTCVideoTrack {
            setRemoteVideoTrack(track)
        } else {
            refreshRemoteVideoTrack()
        }
    }
}
