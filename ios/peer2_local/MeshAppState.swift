import Foundation
import Combine
import CryptoKit
import UserNotifications
import AVFoundation
import WebRTC

enum AppTab: Int, CaseIterable {
    case messenger
    case files
    case calls
    case settings
}

struct ChatMessageItem: Identifiable, Codable {
    let id: String
    let senderId: String
    let text: String
    let timestampMs: Int64
    let isOutgoing: Bool
    let type: String
    let fileName: String?
    let filePath: String?
}

struct ChatGroup: Identifiable, Codable {
    let id: String
    var name: String
    var participantIds: [String]
}

struct CallHistoryEntry: Identifiable, Codable {
    let id: String
    let peerId: String
    let isVideo: Bool
    let outgoing: Bool
    let startedAtMs: Int64
}

struct StreamedFileEntry: Identifiable, Codable {
    let id: String
    let ownerId: String
    let ownerName: String
    let fileName: String
    let mimeType: String
    let totalBytes: Int64
    let sha256: String
    let addedAtMs: Int64

    private enum CodingKeys: String, CodingKey {
        case id, ownerId, ownerName, fileName, mimeType, totalBytes, sha256, addedAtMs
    }

    init(id: String, ownerId: String, ownerName: String, fileName: String, mimeType: String, totalBytes: Int64, sha256: String, addedAtMs: Int64) {
        self.id = id
        self.ownerId = ownerId
        self.ownerName = ownerName
        self.fileName = fileName
        self.mimeType = mimeType
        self.totalBytes = totalBytes
        self.sha256 = sha256
        self.addedAtMs = addedAtMs
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        ownerId = try c.decode(String.self, forKey: .ownerId)
        ownerName = try c.decodeIfPresent(String.self, forKey: .ownerName) ?? ownerId
        fileName = try c.decode(String.self, forKey: .fileName)
        mimeType = try c.decodeIfPresent(String.self, forKey: .mimeType) ?? "application/octet-stream"
        totalBytes = try c.decode(Int64.self, forKey: .totalBytes)
        sha256 = try c.decode(String.self, forKey: .sha256)
        addedAtMs = try c.decode(Int64.self, forKey: .addedAtMs)
    }
}

struct ActiveCallState {
    let callId: String
    let peerId: String
    let isVideo: Bool
    let startedAtMs: Int64
    let outgoing: Bool
}

final class MeshAppState: ObservableObject {
    @Published var identity: LocalIdentity
    @Published var selectedTab: AppTab = .messenger
    @Published var selectedPeerId: String?
    @Published var peers: [MeshPeer] = []
    @Published var unreadByPeer: [String: Int] = [:]
    @Published var rttMs: Double? = nil
    @Published var p95Ms: Double? = nil
    @Published var transferredBytes: Int64 = 0
    @Published var rttSamplesCount: Int = 0
    @Published var outboundFileProgressByPeer: [String: Double] = [:]
    @Published var outboundFileDebugByPeer: [String: FileTransferDebugMetrics] = [:]
    @Published var logs: [String] = []
    @Published var privateChats: [String: [ChatMessageItem]] = [:]
    @Published var groups: [ChatGroup] = []
    @Published var groupChats: [String: [ChatMessageItem]] = [:]
    @Published var callHistory: [CallHistoryEntry] = []
    @Published var myStreamFiles: [StreamedFileEntry] = []
    @Published var remoteStreamFiles: [StreamedFileEntry] = []
    @Published var activeCall: ActiveCallState?
    @Published var incomingCall: ActiveCallState?
    @Published var isCallMicMuted: Bool = false
    @Published var isCallSpeakerEnabled: Bool = true
    @Published var isCallVideoEnabled: Bool = false
    @Published var localCallVideoTrack: RTCVideoTrack?
    @Published var remoteCallVideoTrack: RTCVideoTrack?
    @Published var connectionStatusLabel: String = "LAN"

    private let identityStore = IdentityStore()
    private let service = LanMeshService()
    private let callEngine = WebRTCCallEngine()
    private let ringbackPlayer = CallRingbackPlayer()
    private var signingIdentity: SignedIdentity

    private var cancellables = Set<AnyCancellable>()
    private let defaults = UserDefaults.standard

    private let privateChatsKey = "mesh.privateChats.v1"
    private let groupsKey = "mesh.groups.v1"
    private let groupChatsKey = "mesh.groupChats.v1"
    private let callsKey = "mesh.calls.v1"
    private let myFilesKey = "mesh.my.files.v2"
    private let remoteFilesKey = "mesh.remote.files.v2"
    private let unreadKey = "mesh.unread.v1"
    private let knownNamesKey = "mesh.known.names.v1"
    private let localPathsKey = "mesh.local.paths.v1"
    private let maxDirectFileBytes = 25 * 1024 * 1024

    private var pendingVoiceByFileId: [String: (sender: String, durationMs: Int64)] = [:]
    private var receivedFileById: [String: (sender: String, path: String, name: String?)] = [:]
    private var knownPeerNames: [String: String] = [:]
    private var localStreamPathsById: [String: String] = [:]
    private var fileDebugCompletionWorkItems: [String: DispatchWorkItem] = [:]
    private var pendingIncomingOfferByCallId: [String: String] = [:]
    private var pendingIncomingIceByCallId: [String: [String]] = [:]
    private var callAutoHangupWorkItem: DispatchWorkItem?

    init() {
        let identity = identityStore.loadOrCreateIdentity()
        self.identity = identity
        self.signingIdentity = identityStore.loadOrCreateSigningIdentity(userId: identity.userId)
        requestNotificationAuthorization()
        loadPersisted()
        bindCallEngine()
        bind()
        service.start(identity: identity, signingIdentity: signingIdentity)
    }

    deinit {
        ringbackPlayer.stop()
        cancelCallAutoHangup()
        callEngine.close()
        service.stop()
    }

    func saveProfile(name: String) {
        var updated = identity
        updated.nickname = name
        identity = updated
        identityStore.saveIdentity(updated)
        knownPeerNames[updated.userId] = updated.nickname
        myStreamFiles = myStreamFiles.map {
            StreamedFileEntry(
                id: $0.id,
                ownerId: $0.ownerId,
                ownerName: updated.nickname,
                fileName: $0.fileName,
                mimeType: $0.mimeType,
                totalBytes: $0.totalBytes,
                sha256: $0.sha256,
                addedAtMs: $0.addedAtMs
            )
        }
        signingIdentity = identityStore.loadOrCreateSigningIdentity(userId: updated.userId)
        service.stop()
        service.start(identity: updated, signingIdentity: signingIdentity)
        broadcastMyFilesCatalog()
        persist()
    }

    func sendText(to peerId: String, text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }

        let message = ChatMessageItem(
            id: UUID().uuidString,
            senderId: identity.userId,
            text: trimmed,
            timestampMs: nowMs(),
            isOutgoing: true,
            type: "text",
            fileName: nil,
            filePath: nil
        )
        privateChats[peerId, default: []].append(message)
        persist()

        service.sendContent(.text(trimmed), to: peerId)
    }

    func sendGroupText(groupId: String, text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty,
              let group = groups.first(where: { $0.id == groupId })
        else {
            return
        }

        let message = ChatMessageItem(
            id: UUID().uuidString,
            senderId: identity.userId,
            text: trimmed,
            timestampMs: nowMs(),
            isOutgoing: true,
            type: "group_text",
            fileName: nil,
            filePath: nil
        )
        groupChats[groupId, default: []].append(message)
        persist()

        for participant in group.participantIds where participant != identity.userId {
            let payload: [String: Any] = [
                "text": trimmed,
                "groupId": groupId,
                "groupName": group.name
            ]
            service.sendContent(OutboundContent(kind: .text, payload: payload), to: participant)
        }
    }

    func sendFile(to peerId: String, fileURL: URL) {
        guard let localURL = makeReadableCopy(of: fileURL) else {
            appendTransferError(peerId: peerId, text: "Не удалось прочитать файл")
            return
        }

        guard let data = readStableData(from: localURL) else {
            appendTransferError(peerId: peerId, text: "Файл не готов к отправке")
            try? FileManager.default.removeItem(at: localURL)
            return
        }

        if data.count > maxDirectFileBytes {
            appendTransferError(peerId: peerId, text: "Файл превышает лимит 25 МБ")
            try? FileManager.default.removeItem(at: localURL)
            return
        }

        let suggestedName = fileURL.lastPathComponent.trimmingCharacters(in: .whitespacesAndNewlines)
        let fileName = suggestedName.isEmpty ? localURL.lastPathComponent : suggestedName
        let hash = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        let fileId = UUID().uuidString

        service.resetFileTransferMetrics(for: peerId)
        service.sendContent(
            .fileMeta(
                fileId: fileId,
                fileName: fileName,
                totalBytes: Int64(data.count),
                mimeType: mimeType(for: localURL),
                sha256: hash
            ),
            to: peerId
        )
        outboundFileProgressByPeer[peerId] = 0
        sendFileChunks(fileId: fileId, data: data, to: peerId) { [weak self] progress in
            guard let self else { return }
            DispatchQueue.main.async {
                self.outboundFileProgressByPeer[peerId] = progress
                if progress >= 1 {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                        self.outboundFileProgressByPeer.removeValue(forKey: peerId)
                    }
                }
            }
        }

        privateChats[peerId, default: []].append(
            ChatMessageItem(
                id: UUID().uuidString,
                senderId: identity.userId,
                text: "Файл: \(fileName)",
                timestampMs: nowMs(),
                isOutgoing: true,
                type: "file",
                fileName: fileName,
                filePath: localURL.path
            )
        )
        persist()
    }

    func addStreamFile(from fileURL: URL) {
        guard let localURL = makeReadableCopy(of: fileURL),
              let data = try? Data(contentsOf: localURL)
        else {
            return
        }

        let fileId = UUID().uuidString
        let fileName = localURL.lastPathComponent
        let hash = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        let mime = mimeType(for: localURL)

        let entry = StreamedFileEntry(
            id: fileId,
            ownerId: identity.userId,
            ownerName: identity.nickname,
            fileName: fileName,
            mimeType: mime,
            totalBytes: Int64(data.count),
            sha256: hash,
            addedAtMs: nowMs()
        )
        myStreamFiles.insert(entry, at: 0)
        localStreamPathsById[fileId] = localURL.path
        knownPeerNames[identity.userId] = identity.nickname
        announceStreamFile(entry)
        persist()
    }

    func stopStreaming(fileId: String) {
        myStreamFiles.removeAll { $0.id == fileId }
        if let path = localStreamPathsById.removeValue(forKey: fileId) {
            try? FileManager.default.removeItem(atPath: path)
        }

        let payload: [String: Any] = [
            "streamOp": "remove",
            "fileId": fileId,
            "ownerName": identity.nickname
        ]
        service.sendContent(OutboundContent(kind: .fileMeta, payload: payload), to: nil)
        persist()
    }

    func requestStreamDownload(fileId: String, ownerId: String) {
        let payload: [String: Any] = [
            "action": "download_request",
            "fileId": fileId
        ]
        service.sendContent(OutboundContent(kind: .fileRepairRequest, payload: payload), to: ownerId)
    }

    func removeRemoteStreamFile(fileId: String, ownerId: String) {
        remoteStreamFiles.removeAll { $0.id == fileId && $0.ownerId == ownerId }
        persist()
    }

    func sendVoiceNote(to peerId: String, fileURL: URL, durationMs: Int64) {
        guard let localURL = makeReadableCopy(of: fileURL),
              let data = readStableData(from: localURL)
        else {
            return
        }

        let fileId = UUID().uuidString
        let hash = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        let fileName = "voice_\(fileId.prefix(8)).m4a"
        let payload: [String: Any] = [
            "fileId": fileId,
            "durationMs": durationMs,
            "codec": "aac",
            "fileName": fileName,
            "totalBytes": data.count,
            "sha256": hash
        ]
        service.sendContent(
            .fileMeta(
                fileId: fileId,
                fileName: fileName,
                totalBytes: Int64(data.count),
                mimeType: "audio/mp4",
                sha256: hash
            ),
            to: peerId
        )
        service.sendContent(OutboundContent(kind: .voiceNoteMeta, payload: payload), to: peerId)
        sendFileChunks(fileId: fileId, data: data, to: peerId)

        let local = ChatMessageItem(
            id: UUID().uuidString,
            senderId: identity.userId,
            text: "Голосовое сообщение",
            timestampMs: nowMs(),
            isOutgoing: true,
            type: "voice",
            fileName: fileName,
            filePath: localURL.path
        )
        privateChats[peerId, default: []].append(local)
        persist()
    }

    func sendVideoNote(to peerId: String, durationMs: Int64) {
        let fileId = UUID().uuidString
        let payload: [String: Any] = [
            "fileId": fileId,
            "durationMs": durationMs,
            "width": 720,
            "height": 1280,
            "codec": "h264"
        ]
        service.sendContent(OutboundContent(kind: .videoNoteMeta, payload: payload), to: peerId)
        let local = ChatMessageItem(
            id: UUID().uuidString,
            senderId: identity.userId,
            text: "Видеосообщение",
            timestampMs: nowMs(),
            isOutgoing: true,
            type: "video_note",
            fileName: nil,
            filePath: nil
        )
        privateChats[peerId, default: []].append(local)
        persist()
    }

    func createGroup(name: String, participants: [String]) {
        guard !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        guard !participants.isEmpty else { return }

        let group = ChatGroup(
            id: UUID().uuidString,
            name: name,
            participantIds: [identity.userId] + participants
        )
        groups.append(group)
        persist()

        for participant in participants {
            let payload: [String: Any] = [
                "text": "",
                "groupId": group.id,
                "groupName": group.name,
                "system": "group_invite"
            ]
            service.sendContent(OutboundContent(kind: .text, payload: payload), to: participant)
        }
    }

    func removeChat(peerId: String) {
        privateChats.removeValue(forKey: peerId)
        unreadByPeer.removeValue(forKey: peerId)
        persist()
    }

    func openChat(peerId: String) {
        selectedTab = .messenger
        selectedPeerId = peerId
        markChatRead(peerId: peerId)
    }

    func markChatRead(peerId: String) {
        if unreadByPeer[peerId] != 0 {
            unreadByPeer[peerId] = 0
            persist()
        }
    }

    func displayName(for peerId: String) -> String {
        if peerId == identity.userId {
            return identity.nickname
        }
        if let peer = peers.first(where: { $0.userId == peerId }) {
            let nickname = peer.nickname.trimmingCharacters(in: .whitespacesAndNewlines)
            if !nickname.isEmpty && nickname != peerId {
                return nickname
            }
            let device = peer.device.trimmingCharacters(in: .whitespacesAndNewlines)
            if !device.isEmpty { return device }
            return peerId
        }
        if let known = knownPeerNames[peerId], !known.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return known
        }
        return peerId
    }

    func resolveExistingFilePath(filePath: String?, fileName: String?) -> String? {
        if let path = filePath,
           !path.isEmpty,
           FileManager.default.fileExists(atPath: path) {
            return path
        }

        guard let fileName, !fileName.isEmpty else { return nil }
        let base = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        let received = base.appendingPathComponent("received", isDirectory: true).appendingPathComponent(fileName).path
        if FileManager.default.fileExists(atPath: received) {
            return received
        }
        let outgoing = base.appendingPathComponent("outgoing", isDirectory: true).appendingPathComponent(fileName).path
        if FileManager.default.fileExists(atPath: outgoing) {
            return outgoing
        }
        return nil
    }

    func startCall(peerId: String, video: Bool) {
        let callId = UUID().uuidString
        let state = ActiveCallState(
            callId: callId,
            peerId: peerId,
            isVideo: video,
            startedAtMs: nowMs(),
            outgoing: true
        )
        activeCall = state
        resetCallControls()
        callEngine.setMicrophoneMuted(isCallMicMuted)
        callEngine.setSpeakerEnabled(isCallSpeakerEnabled)
        isCallVideoEnabled = video

        callEngine.startOutgoing(callId: callId, peerId: peerId, isVideo: video)
        ringbackPlayer.start()
        scheduleCallAutoHangup(callId: callId, peerId: peerId)

        callHistory.insert(
            CallHistoryEntry(
                id: UUID().uuidString,
                peerId: peerId,
                isVideo: video,
                outgoing: true,
                startedAtMs: nowMs()
            ),
            at: 0
        )
        persist()
    }

    func acceptIncomingCall() {
        guard let incoming = incomingCall else { return }
        ringbackPlayer.stop()
        cancelCallAutoHangup()
        activeCall = ActiveCallState(
            callId: incoming.callId,
            peerId: incoming.peerId,
            isVideo: incoming.isVideo,
            startedAtMs: nowMs(),
            outgoing: false
        )
        resetCallControls()
        callEngine.setMicrophoneMuted(isCallMicMuted)
        callEngine.setSpeakerEnabled(isCallSpeakerEnabled)
        isCallVideoEnabled = incoming.isVideo
        incomingCall = nil
        if let remoteOffer = pendingIncomingOfferByCallId.removeValue(forKey: incoming.callId),
           remoteOffer.hasPrefix("offer\n") {
            callEngine.acceptIncoming(
                callId: incoming.callId,
                peerId: incoming.peerId,
                isVideo: incoming.isVideo,
                remoteOfferPayload: remoteOffer
            )
            let bufferedIce = pendingIncomingIceByCallId.removeValue(forKey: incoming.callId) ?? []
            for icePayload in bufferedIce {
                callEngine.handleRemoteIce(callId: incoming.callId, payload: icePayload)
            }
        } else {
            service.sendContent(.callSignal(callId: incoming.callId, phase: "answer", sdpOrIce: "ok"), to: incoming.peerId)
        }
    }

    func declineIncomingCall() {
        guard let incoming = incomingCall else { return }
        pendingIncomingOfferByCallId.removeValue(forKey: incoming.callId)
        pendingIncomingIceByCallId.removeValue(forKey: incoming.callId)
        service.sendContent(.callSignal(callId: incoming.callId, phase: "end", sdpOrIce: "decline"), to: incoming.peerId)
        incomingCall = nil
    }

    func endActiveCall() {
        guard let active = activeCall else { return }
        ringbackPlayer.stop()
        cancelCallAutoHangup()
        callEngine.close(callId: active.callId)
        service.sendContent(.callSignal(callId: active.callId, phase: "end", sdpOrIce: "bye"), to: active.peerId)
        activeCall = nil
        localCallVideoTrack = nil
        remoteCallVideoTrack = nil
        resetCallControls()
    }

    func toggleCallMicrophone() {
        isCallMicMuted.toggle()
        callEngine.setMicrophoneMuted(isCallMicMuted)
    }

    func toggleCallSpeaker() {
        isCallSpeakerEnabled.toggle()
        callEngine.setSpeakerEnabled(isCallSpeakerEnabled)
    }

    func toggleCallVideo() {
        guard let active = activeCall else { return }
        let target = !isCallVideoEnabled

        if target {
            switch AVCaptureDevice.authorizationStatus(for: .video) {
            case .authorized:
                applyVideoState(target, for: active)
            case .notDetermined:
                AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                    guard let self else { return }
                    guard granted else { return }
                    DispatchQueue.main.async {
                        guard let latest = self.activeCall, latest.callId == active.callId else { return }
                        self.applyVideoState(true, for: latest)
                    }
                }
            case .denied, .restricted:
                break
            @unknown default:
                break
            }
        } else {
            applyVideoState(false, for: active)
        }
    }

    func clearCallHistory() {
        callHistory.removeAll()
        persist()
    }

    func clearAllData() {
        privateChats.removeAll()
        unreadByPeer.removeAll()
        outboundFileProgressByPeer.removeAll()
        outboundFileDebugByPeer.removeAll()
        groupChats.removeAll()
        groups.removeAll()
        callHistory.removeAll()
        myStreamFiles.removeAll()
        remoteStreamFiles.removeAll()
        pendingVoiceByFileId.removeAll()
        receivedFileById.removeAll()
        pendingIncomingOfferByCallId.removeAll()
        pendingIncomingIceByCallId.removeAll()
        knownPeerNames.removeAll()
        localStreamPathsById.removeAll()
        fileDebugCompletionWorkItems.values.forEach { $0.cancel() }
        fileDebugCompletionWorkItems.removeAll()
        ringbackPlayer.stop()
        cancelCallAutoHangup()
        localCallVideoTrack = nil
        remoteCallVideoTrack = nil
        resetCallControls()
        callEngine.close()
        persist()
    }

    private func bindCallEngine() {
        callEngine.onSignal = { [weak self] peerId, callId, phase, sdpOrIce in
            guard let self else { return }
            self.service.sendContent(.callSignal(callId: callId, phase: phase, sdpOrIce: sdpOrIce), to: peerId)
        }

        callEngine.onVideoTracksChanged = { [weak self] local, remote in
            DispatchQueue.main.async {
                self?.localCallVideoTrack = local
                self?.remoteCallVideoTrack = remote
            }
        }

        callEngine.onConnected = { [weak self] _ in
            DispatchQueue.main.async {
                self?.ringbackPlayer.stop()
                self?.cancelCallAutoHangup()
            }
        }

        callEngine.onDisconnected = { [weak self] callId in
            guard let self else { return }
            DispatchQueue.main.async {
                self.ringbackPlayer.stop()
                self.cancelCallAutoHangup()
                if self.activeCall?.callId == callId {
                    self.activeCall = nil
                }
                if self.incomingCall?.callId == callId {
                    self.incomingCall = nil
                }
                self.pendingIncomingOfferByCallId.removeValue(forKey: callId)
                self.pendingIncomingIceByCallId.removeValue(forKey: callId)
                self.localCallVideoTrack = nil
                self.remoteCallVideoTrack = nil
                self.resetCallControls()
            }
        }
    }

    private func bind() {
        service.$peers
            .receive(on: DispatchQueue.main)
            .sink { [weak self] peers in
                guard let self else { return }
                self.peers = peers
                self.connectionStatusLabel = peers.isEmpty ? "Нет пиров" : "LAN: \(peers.count)"
                for peer in peers {
                    if self.privateChats[peer.userId] == nil {
                        self.privateChats[peer.userId] = []
                    }
                    self.knownPeerNames[peer.userId] = peer.nickname
                }
                self.knownPeerNames[self.identity.userId] = self.identity.nickname
            }
            .store(in: &cancellables)

        service.$logs
            .receive(on: DispatchQueue.main)
            .sink { [weak self] logs in
                self?.logs = logs
            }
            .store(in: &cancellables)

        service.$fileTransferDebugByPeer
            .receive(on: DispatchQueue.main)
            .sink { [weak self] metrics in
                guard let self else { return }
                self.outboundFileDebugByPeer = metrics

                for (peerId, metric) in metrics {
                    let total = max(metric.totalChunks, metric.ackedChunks + metric.pendingChunks + metric.failedChunks)
                    let isComplete = total > 0
                        && metric.pendingChunks == 0
                        && (metric.ackedChunks + metric.failedChunks) >= total
                    if isComplete {
                        if self.fileDebugCompletionWorkItems[peerId] == nil {
                            let work = DispatchWorkItem { [weak self] in
                                guard let self else { return }
                                self.outboundFileProgressByPeer.removeValue(forKey: peerId)
                                self.fileDebugCompletionWorkItems.removeValue(forKey: peerId)
                                self.service.resetFileTransferMetrics(for: peerId)
                            }
                            self.fileDebugCompletionWorkItems[peerId] = work
                            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5, execute: work)
                        }
                    } else {
                        self.fileDebugCompletionWorkItems[peerId]?.cancel()
                        self.fileDebugCompletionWorkItems.removeValue(forKey: peerId)
                    }
                }

                let activePeerIds = Set(metrics.keys)
                let staleItems = self.fileDebugCompletionWorkItems.keys.filter { !activePeerIds.contains($0) }
                for peerId in staleItems {
                    self.fileDebugCompletionWorkItems[peerId]?.cancel()
                    self.fileDebugCompletionWorkItems.removeValue(forKey: peerId)
                }
            }
            .store(in: &cancellables)

        service.$incomingMessages
            .receive(on: DispatchQueue.main)
            .sink { [weak self] messages in
                self?.consume(messages: messages)
            }
            .store(in: &cancellables)
    }

    private var consumedIds: Set<String> = []

    private func consume(messages: [ReceivedMeshMessage]) {
        for message in messages {
            if consumedIds.contains(message.id) { continue }
            consumedIds.insert(message.id)

            if let content = message.content {
                handle(content: content, sender: message.fromUserId, receivedPath: message.receivedFilePath, receivedName: message.receivedFileName)
            } else if let plain = message.decryptedText {
                let item = ChatMessageItem(
                    id: message.id,
                    senderId: message.fromUserId,
                    text: plain,
                    timestampMs: message.envelope.timestampMs,
                    isOutgoing: false,
                    type: "text",
                    fileName: nil,
                    filePath: nil
                )
                appendIncomingMessage(peerId: message.fromUserId, message: item)
            }
        }
        persist()
    }

    private func handle(content: OutboundContent, sender: String, receivedPath: String?, receivedName: String?) {
        switch content.kind {
        case .text:
            let text = content.payload["text"] as? String ?? ""
            if let groupId = content.payload["groupId"] as? String {
                let groupName = content.payload["groupName"] as? String ?? "Группа"
                let isInvite = (content.payload["system"] as? String) == "group_invite"
                let message = ChatMessageItem(
                    id: UUID().uuidString,
                    senderId: sender,
                    text: text,
                    timestampMs: nowMs(),
                    isOutgoing: false,
                    type: "group_text",
                    fileName: nil,
                    filePath: nil
                )
                if !isInvite {
                    groupChats[groupId, default: []].append(message)
                    let senderName = displayName(for: sender)
                    let body = text.isEmpty ? "Новое сообщение в группе" : "\(senderName): \(text)"
                    postLocalNotification(title: groupName, text: body)
                }

                if groups.first(where: { $0.id == groupId }) == nil {
                    groups.append(ChatGroup(id: groupId, name: groupName, participantIds: [sender, identity.userId]))
                }
            } else {
                let item = ChatMessageItem(
                    id: UUID().uuidString,
                    senderId: sender,
                    text: text,
                    timestampMs: nowMs(),
                    isOutgoing: false,
                    type: "text",
                    fileName: nil,
                    filePath: nil
                )
                appendIncomingMessage(peerId: sender, message: item)
            }

        case .fileMeta:
            if let streamOp = content.payload["streamOp"] as? String {
                if streamOp == "advertise",
                   let fileId = content.payload["fileId"] as? String {
                    let ownerName = (content.payload["ownerName"] as? String) ?? displayName(for: sender)
                    let entry = StreamedFileEntry(
                        id: fileId,
                        ownerId: sender,
                        ownerName: ownerName,
                        fileName: content.payload["fileName"] as? String ?? "file",
                        mimeType: content.payload["mimeType"] as? String ?? "application/octet-stream",
                        totalBytes: Int64(content.payload["totalBytes"] as? Int ?? 0),
                        sha256: content.payload["sha256"] as? String ?? "",
                        addedAtMs: nowMs()
                    )
                    knownPeerNames[sender] = ownerName
                    remoteStreamFiles.removeAll { $0.id == entry.id && $0.ownerId == sender }
                    remoteStreamFiles.insert(entry, at: 0)
                } else if streamOp == "remove",
                          let fileId = content.payload["fileId"] as? String {
                    remoteStreamFiles.removeAll { $0.id == fileId && $0.ownerId == sender }
                }
            } else if (content.payload["transferType"] as? String) == "stream" {
                break
            }

        case .fileChunk:
            guard let fileId = content.payload["fileId"] as? String else { return }
            if let path = receivedPath {
                receivedFileById[fileId] = (sender, path, receivedName)

                let hasPendingVoice = pendingVoiceByFileId[fileId]?.sender == sender
                if hasPendingVoice {
                    pendingVoiceByFileId.removeValue(forKey: fileId)
                }
                let inferredVoice = hasPendingVoice || isLikelyVoiceFile(name: receivedName, path: path)
                if inferredVoice {
                    let voiceMessage = ChatMessageItem(
                        id: UUID().uuidString,
                        senderId: sender,
                        text: "Голосовое сообщение",
                        timestampMs: nowMs(),
                        isOutgoing: false,
                        type: "voice",
                        fileName: receivedName,
                        filePath: path
                    )
                    appendIncomingMessage(peerId: sender, message: voiceMessage)
                    return
                }

                let message = ChatMessageItem(
                    id: UUID().uuidString,
                    senderId: sender,
                    text: "Файл получен",
                    timestampMs: nowMs(),
                    isOutgoing: false,
                    type: "file",
                    fileName: receivedName,
                    filePath: path
                )
                appendIncomingMessage(peerId: sender, message: message)
            }

        case .callSignal:
            let callId = content.payload["callId"] as? String ?? UUID().uuidString
            let phase = content.payload["phase"] as? String ?? ""
            let media = content.payload["sdpOrIce"] as? String ?? "audio"
            let isVideo = media.contains("video") || media.contains("m=video")

            if phase == "offer" {
                if let active = activeCall, active.peerId == sender, active.callId == callId {
                    if active.isVideo != isVideo {
                        activeCall = ActiveCallState(
                            callId: active.callId,
                            peerId: active.peerId,
                            isVideo: isVideo,
                            startedAtMs: active.startedAtMs,
                            outgoing: active.outgoing
                        )
                    }
                    if isVideo {
                        isCallVideoEnabled = true
                    }
                    if media.hasPrefix("offer\n") {
                        callEngine.handleRemoteOffer(callId: callId, payload: media)
                    }
                    return
                }
                if media.hasPrefix("offer\n") {
                    pendingIncomingOfferByCallId[callId] = media
                }
                incomingCall = ActiveCallState(
                    callId: callId,
                    peerId: sender,
                    isVideo: isVideo,
                    startedAtMs: nowMs(),
                    outgoing: false
                )
                callHistory.insert(
                    CallHistoryEntry(id: UUID().uuidString, peerId: sender, isVideo: isVideo, outgoing: false, startedAtMs: nowMs()),
                    at: 0
                )
            } else if phase == "answer" {
                if let active = activeCall, active.peerId == sender, active.callId == callId {
                    ringbackPlayer.stop()
                    cancelCallAutoHangup()
                    if media.hasPrefix("answer\n") {
                        callEngine.handleRemoteAnswer(callId: callId, payload: media)
                    }
                    let updated = ActiveCallState(
                        callId: active.callId,
                        peerId: active.peerId,
                        isVideo: isCallVideoEnabled || isVideo,
                        startedAtMs: active.startedAtMs,
                        outgoing: active.outgoing
                    )
                    activeCall = updated
                }
            } else if phase == "ice" {
                if activeCall?.callId == callId {
                    callEngine.handleRemoteIce(callId: callId, payload: media)
                } else if incomingCall?.callId == callId {
                    pendingIncomingIceByCallId[callId, default: []].append(media)
                }
            } else if phase == "end" {
                pendingIncomingOfferByCallId.removeValue(forKey: callId)
                pendingIncomingIceByCallId.removeValue(forKey: callId)
                ringbackPlayer.stop()
                cancelCallAutoHangup()
                callEngine.close(callId: callId)
                if activeCall?.peerId == sender {
                    activeCall = nil
                }
                if incomingCall?.peerId == sender {
                    incomingCall = nil
                }
                localCallVideoTrack = nil
                remoteCallVideoTrack = nil
                resetCallControls()
            }

        case .voiceNoteMeta:
            guard let fileId = content.payload["fileId"] as? String else { return }
            let durationMs = Int64(content.payload["durationMs"] as? Int ?? 0)

            if let ready = receivedFileById.removeValue(forKey: fileId), ready.sender == sender {
                let voice = ChatMessageItem(
                    id: UUID().uuidString,
                    senderId: sender,
                    text: "Голосовое сообщение",
                    timestampMs: nowMs(),
                    isOutgoing: false,
                    type: "voice",
                    fileName: ready.name,
                    filePath: ready.path
                )
                appendIncomingMessage(peerId: sender, message: voice)
            } else {
                pendingVoiceByFileId[fileId] = (sender, durationMs)
            }

        case .videoNoteMeta:
            let message = ChatMessageItem(
                id: UUID().uuidString,
                senderId: sender,
                text: "Видеосообщение",
                timestampMs: nowMs(),
                isOutgoing: false,
                type: "video_note",
                fileName: nil,
                filePath: nil
            )
            appendIncomingMessage(peerId: sender, message: message)

        case .fileRepairRequest:
            guard let action = content.payload["action"] as? String else { return }
            if action == "download_request",
               let fileId = content.payload["fileId"] as? String,
               let stream = myStreamFiles.first(where: { $0.id == fileId }),
               let path = localStreamPathsById[fileId],
               let data = try? Data(contentsOf: URL(fileURLWithPath: path)) {
                let metaPayload: [String: Any] = [
                    "fileId": stream.id,
                    "fileName": stream.fileName,
                    "totalBytes": Int(data.count),
                    "mimeType": stream.mimeType,
                    "sha256": stream.sha256,
                    "transferType": "stream"
                ]
                service.sendContent(OutboundContent(kind: .fileMeta, payload: metaPayload), to: sender)
                sendFileChunks(fileId: stream.id, data: data, to: sender)
            }

        case .audioPacket:
            break
        }
    }

    private func loadPersisted() {
        if let data = defaults.data(forKey: privateChatsKey),
           let value = try? JSONDecoder().decode([String: [ChatMessageItem]].self, from: data) {
            privateChats = value
        }
        if let data = defaults.data(forKey: groupsKey),
           let value = try? JSONDecoder().decode([ChatGroup].self, from: data) {
            groups = value
        }
        if let data = defaults.data(forKey: groupChatsKey),
           let value = try? JSONDecoder().decode([String: [ChatMessageItem]].self, from: data) {
            groupChats = value
        }
        if let data = defaults.data(forKey: callsKey),
           let value = try? JSONDecoder().decode([CallHistoryEntry].self, from: data) {
            callHistory = value
        }
        if let data = defaults.data(forKey: myFilesKey),
           let value = try? JSONDecoder().decode([StreamedFileEntry].self, from: data) {
            myStreamFiles = value
        } else if let data = defaults.data(forKey: "mesh.local.file.v1"),
                  let legacy = try? JSONDecoder().decode(StreamedFileEntry.self, from: data) {
            myStreamFiles = [legacy]
        }
        if let data = defaults.data(forKey: remoteFilesKey),
           let value = try? JSONDecoder().decode([StreamedFileEntry].self, from: data) {
            remoteStreamFiles = value
        } else if let data = defaults.data(forKey: "mesh.available.files.v1"),
                  let legacy = try? JSONDecoder().decode([StreamedFileEntry].self, from: data) {
            remoteStreamFiles = legacy
        }
        if let data = defaults.data(forKey: unreadKey),
           let value = try? JSONDecoder().decode([String: Int].self, from: data) {
            unreadByPeer = value
        }
        if let data = defaults.data(forKey: knownNamesKey),
           let value = try? JSONDecoder().decode([String: String].self, from: data) {
            knownPeerNames = value
        }
        if let data = defaults.data(forKey: localPathsKey),
           let value = try? JSONDecoder().decode([String: String].self, from: data) {
            localStreamPathsById = value
        }

        knownPeerNames[identity.userId] = identity.nickname
    }

    private func persist() {
        defaults.set(try? JSONEncoder().encode(privateChats), forKey: privateChatsKey)
        defaults.set(try? JSONEncoder().encode(groups), forKey: groupsKey)
        defaults.set(try? JSONEncoder().encode(groupChats), forKey: groupChatsKey)
        defaults.set(try? JSONEncoder().encode(callHistory), forKey: callsKey)
        defaults.set(try? JSONEncoder().encode(myStreamFiles), forKey: myFilesKey)
        defaults.set(try? JSONEncoder().encode(remoteStreamFiles), forKey: remoteFilesKey)
        defaults.set(try? JSONEncoder().encode(unreadByPeer), forKey: unreadKey)
        defaults.set(try? JSONEncoder().encode(knownPeerNames), forKey: knownNamesKey)
        defaults.set(try? JSONEncoder().encode(localStreamPathsById), forKey: localPathsKey)
    }

    private func nowMs() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }

    private func mimeType(for url: URL) -> String {
        switch url.pathExtension.lowercased() {
        case "jpg", "jpeg": return "image/jpeg"
        case "png": return "image/png"
        case "mp4": return "video/mp4"
        case "mov": return "video/quicktime"
        case "m4a": return "audio/mp4"
        case "aac": return "audio/aac"
        case "mp3": return "audio/mpeg"
        case "wav": return "audio/wav"
        case "pdf": return "application/pdf"
        default: return "application/octet-stream"
        }
    }

    private func announceStreamFile(_ entry: StreamedFileEntry) {
        let payload: [String: Any] = [
            "streamOp": "advertise",
            "fileId": entry.id,
            "fileName": entry.fileName,
            "mimeType": entry.mimeType,
            "totalBytes": Int(entry.totalBytes),
            "sha256": entry.sha256,
            "ownerName": entry.ownerName
        ]
        service.sendContent(OutboundContent(kind: .fileMeta, payload: payload), to: nil)
    }

    private func broadcastMyFilesCatalog() {
        for entry in myStreamFiles {
            announceStreamFile(entry)
        }
    }

    private func sendFileChunks(
        fileId: String,
        data: Data,
        to peerId: String,
        onProgress: ((Double) -> Void)? = nil
    ) {
        let chunkSize = 8 * 1024
        let total = Int(ceil(Double(data.count) / Double(chunkSize)))
        guard total > 0 else {
            onProgress?(1)
            return
        }
        DispatchQueue.global(qos: .utility).async { [weak self] in
            guard let self else { return }
            for index in 0..<total {
                let start = index * chunkSize
                let end = min(data.count, start + chunkSize)
                let chunk = data.subdata(in: start..<end)
                self.service.sendContent(
                    .fileChunk(
                        fileId: fileId,
                        chunkIndex: index,
                        chunkTotal: total,
                        chunkBase64: chunk.base64EncodedString()
                    ),
                    to: peerId
                )
                onProgress?(Double(index + 1) / Double(total))
                if index % 24 == 23 {
                    Thread.sleep(forTimeInterval: 0.08)
                } else {
                    Thread.sleep(forTimeInterval: 0.025)
                }
            }
        }
    }

    private func makeReadableCopy(of url: URL) -> URL? {
        let accessed = url.startAccessingSecurityScopedResource()
        defer {
            if accessed {
                url.stopAccessingSecurityScopedResource()
            }
        }

        let base = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        let dir = base.appendingPathComponent("outgoing", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)

        let ext = url.pathExtension.isEmpty ? "bin" : url.pathExtension
        let safeName = url.deletingPathExtension().lastPathComponent.replacingOccurrences(of: "/", with: "_")
        let local = dir.appendingPathComponent("\(safeName)_\(UUID().uuidString.prefix(6)).\(ext)")

        do {
            if FileManager.default.fileExists(atPath: local.path) {
                try FileManager.default.removeItem(at: local)
            }
            try FileManager.default.copyItem(at: url, to: local)
            return local
        } catch {
            guard let data = try? Data(contentsOf: url) else { return nil }
            try? data.write(to: local, options: .atomic)
            return local
        }
    }

    private func readStableData(from url: URL) -> Data? {
        var lastCount = -1
        for _ in 0..<8 {
            if let data = try? Data(contentsOf: url), !data.isEmpty {
                if data.count == lastCount {
                    return data
                }
                lastCount = data.count
            }
            Thread.sleep(forTimeInterval: 0.05)
        }
        if let data = try? Data(contentsOf: url), !data.isEmpty {
            return data
        }
        return nil
    }

    private func appendIncomingMessage(peerId: String, message: ChatMessageItem) {
        privateChats[peerId, default: []].append(message)

        if !(selectedTab == .messenger && selectedPeerId == peerId) {
            unreadByPeer[peerId, default: 0] += 1
            postLocalNotification(title: displayName(for: peerId), text: message.text)
        }
    }

    private func appendTransferError(peerId: String, text: String) {
        let message = ChatMessageItem(
            id: UUID().uuidString,
            senderId: identity.userId,
            text: text,
            timestampMs: nowMs(),
            isOutgoing: true,
            type: "text",
            fileName: nil,
            filePath: nil
        )
        privateChats[peerId, default: []].append(message)
        persist()
    }

    private func requestNotificationAuthorization() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) { _, _ in }
    }

    private func postLocalNotification(title: String, text: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = text.isEmpty ? "Новое сообщение" : text
        content.sound = .default

        let request = UNNotificationRequest(
            identifier: "peer.\(UUID().uuidString)",
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request)
    }

    private func resetCallControls() {
        isCallMicMuted = false
        isCallSpeakerEnabled = true
        isCallVideoEnabled = false
    }

    private func applyVideoState(_ enabled: Bool, for active: ActiveCallState) {
        isCallVideoEnabled = enabled
        activeCall = ActiveCallState(
            callId: active.callId,
            peerId: active.peerId,
            isVideo: enabled,
            startedAtMs: active.startedAtMs,
            outgoing: active.outgoing
        )
        callEngine.setVideoEnabled(callId: active.callId, enabled: enabled)
    }

    private func scheduleCallAutoHangup(callId: String, peerId: String) {
        cancelCallAutoHangup()
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            guard let active = self.activeCall, active.callId == callId, active.outgoing else { return }
            self.ringbackPlayer.stop()
            self.callEngine.close(callId: callId)
            self.service.sendContent(.callSignal(callId: callId, phase: "end", sdpOrIce: "timeout_45s"), to: peerId)
            self.activeCall = nil
            self.localCallVideoTrack = nil
            self.remoteCallVideoTrack = nil
            self.resetCallControls()
            self.callAutoHangupWorkItem = nil
        }
        callAutoHangupWorkItem = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 45, execute: work)
    }

    private func cancelCallAutoHangup() {
        callAutoHangupWorkItem?.cancel()
        callAutoHangupWorkItem = nil
    }

    private func isLikelyVoiceFile(name: String?, path: String) -> Bool {
        let normalizedName = (name ?? "").lowercased()
        if normalizedName.hasPrefix("voice_") || normalizedName.hasSuffix(".m4a") || normalizedName.hasSuffix(".aac") {
            return true
        }
        let ext = URL(fileURLWithPath: path).pathExtension.lowercased()
        return ext == "m4a" || ext == "aac"
    }
}
