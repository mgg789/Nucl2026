import Foundation
import CryptoKit
import Combine

struct MeshPeer: Identifiable, Equatable {
    var id: String { userId }
    let userId: String
    let nickname: String
    let host: String
    let port: UInt16
    let device: String
    let lastSeenMs: Int64
}

struct ReceivedMeshMessage: Identifiable {
    let id: String
    let fromUserId: String
    let envelope: MeshEnvelope
    let decryptedText: String?
    let accessGranted: Bool
    let content: OutboundContent?
    let contentSummary: String
    let receivedFilePath: String?
    let receivedFileName: String?
}

struct FileTransferDebugMetrics: Equatable {
    var fileId: String
    var totalChunks: Int
    var pendingChunks: Int
    var ackedChunks: Int
    var retriedChunks: Int
    var failedChunks: Int
    var lastEventMs: Int64
}

private struct PendingAck {
    let envelope: MeshEnvelope
    let targetUserId: String?
    let contentKind: ContentKind
    let fileId: String?
    let chunkTotal: Int?
    var retries: Int
    let maxRetries: Int
    var lastAttemptMs: Int64
}

private struct IncomingFileState {
    var fileName: String?
    var expectedHash: String?
    var chunkTotal: Int?
    var chunks: [Int: Data]
}

final class LanMeshService: NSObject, ObservableObject {
    @Published private(set) var isRunning = false
    @Published private(set) var peers: [MeshPeer] = []
    @Published private(set) var incomingMessages: [ReceivedMeshMessage] = []
    @Published private(set) var logs: [String] = []
    @Published private(set) var ackedCount = 0
    @Published private(set) var failedCount = 0
    @Published private(set) var fileTransferDebugByPeer: [String: FileTransferDebugMetrics] = [:]

    private let queue = DispatchQueue(label: "com.peer.mesh.lan", qos: .userInitiated)
    private let tcpWriteQueue = DispatchQueue(label: "com.peer.mesh.lan.tcp.write", qos: .utility)
    private let tcpReadQueue = DispatchQueue(label: "com.peer.mesh.lan.tcp.read", qos: .userInitiated, attributes: .concurrent)

    private let discoveryPort: UInt16 = 39888
    private let tcpPort: UInt16 = 39889

    private var udpSocket: Int32 = -1
    private var tcpServerSocket: Int32 = -1

    private var udpReadSource: DispatchSourceRead?
    private var tcpAcceptSource: DispatchSourceRead?
    private var timer: DispatchSourceTimer?

    private var identity: LocalIdentity?
    private var signingIdentity: SignedIdentity?

    private var peersByUserId: [String: MeshPeer] = [:]
    private var seenMessageIds: Set<String> = []
    private var pendingAcks: [String: PendingAck] = [:]
    private var fileTransferDebugByPeerStore: [String: FileTransferDebugMetrics] = [:]
    private var incomingFiles: [String: IncomingFileState] = [:]
    private var completedFiles: [String: (name: String, path: String)] = [:]
    private let bonjourServiceType = "_peermesh._tcp."
    private let bonjourDomain = "local."
    private var publishedService: NetService?
    private var serviceBrowser: NetServiceBrowser?
    private var resolvingServices: [String: NetService] = [:]
    private var bonjourPeersByUserId: [String: (nickname: String, host: String, port: UInt16, device: String)] = [:]
    private var serviceNameToUserId: [String: String] = [:]

    func start(identity: LocalIdentity, signingIdentity: SignedIdentity) {
        queue.async {
            guard !self.isRunning else { return }
            self.identity = identity
            self.signingIdentity = signingIdentity

            guard self.setupUDPSocket(), self.setupTCPServer() else {
                self.log("LAN start failed")
                self.stopInternal()
                return
            }

            self.isRunning = true
            self.publish()
            self.log("LAN mesh started on UDP:\(self.discoveryPort) TCP:\(self.tcpPort)")

            self.startTimer()
            self.sendHello()
            DispatchQueue.main.async { [weak self] in
                self?.startBonjour()
            }
        }
    }

    func stop() {
        queue.async {
            self.stopInternal()
        }
    }

    func resetFileTransferMetrics(for peerId: String) {
        queue.async {
            self.fileTransferDebugByPeerStore.removeValue(forKey: peerId)
            self.publishFileTransferDebug()
        }
    }

    func sendContent(_ content: OutboundContent, policy: AccessPolicy = AccessPolicy(), to targetUserId: String? = nil) {
        queue.async {
            guard let identity = self.identity,
                  let signingIdentity = self.signingIdentity,
                  let (keyId, keyBytes) = PolicyKeyService.buildSendKey(sender: identity, policy: policy),
                  var envelope = MeshEnvelope.createChat(
                    sender: identity,
                    senderPublicKeyBase64: signingIdentity.publicKeyBase64,
                    keyId: keyId,
                    keyBytes: keyBytes,
                    messageText: ContentCodec.encode(content),
                    policy: policy,
                    ttl: 2
                  )
            else {
                return
            }

            guard let signature = signingIdentity.sign(envelope.signingString()) else {
                self.log("Failed to sign envelope")
                return
            }
            envelope.signatureBase64 = signature

            let streamOp = content.payload["streamOp"] as? String
            let requiresAck = content.kind != .audioPacket
                && !(content.kind == .fileMeta && (streamOp == "advertise" || streamOp == "remove"))

            if requiresAck {
                let fileId = content.payload["fileId"] as? String
                let chunkTotal = content.payload["chunkTotal"] as? Int
                let maxRetries = content.kind == .fileChunk ? 120 : 4
                self.pendingAcks[envelope.id] = PendingAck(
                    envelope: envelope,
                    targetUserId: targetUserId,
                    contentKind: content.kind,
                    fileId: fileId,
                    chunkTotal: chunkTotal,
                    retries: 0,
                    maxRetries: maxRetries,
                    lastAttemptMs: Self.nowMs()
                )

                if content.kind == .fileChunk,
                   let peerId = targetUserId,
                   let fileId {
                    self.trackFileChunkEnqueued(peerId: peerId, fileId: fileId, chunkTotal: chunkTotal ?? 0)
                }
            }

            self.seenMessageIds.insert(envelope.id)
            self.sendEnvelope(envelope, to: targetUserId)
        }
    }

    private func sendEnvelope(_ envelope: MeshEnvelope, to targetUserId: String?) {
        guard let data = envelope.toData() else { return }

        let targets: [MeshPeer]
        if let user = targetUserId {
            targets = peersByUserId[user].map { [$0] } ?? []
        } else {
            targets = Array(peersByUserId.values)
        }

        for peer in targets {
            let host = peer.host
            let port = peer.port
            tcpWriteQueue.async { [weak self] in
                self?.sendPacket(data, host: host, port: port)
            }
        }
    }

    private func setupUDPSocket() -> Bool {
        let fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard fd >= 0 else {
            log("UDP socket create failed")
            return false
        }

        var reuse: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &reuse, socklen_t(MemoryLayout<Int32>.size))
        setsockopt(fd, SOL_SOCKET, SO_BROADCAST, &reuse, socklen_t(MemoryLayout<Int32>.size))

        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = discoveryPort.bigEndian
        addr.sin_addr = in_addr(s_addr: INADDR_ANY)

        let bindResult = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }

        guard bindResult >= 0 else {
            log("UDP bind failed")
            close(fd)
            return false
        }

        udpSocket = fd
        let source = DispatchSource.makeReadSource(fileDescriptor: fd, queue: queue)
        source.setEventHandler { [weak self] in
            self?.readUDP()
        }
        source.setCancelHandler {
            close(fd)
        }
        source.resume()
        udpReadSource = source
        return true
    }

    private func setupTCPServer() -> Bool {
        let fd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
        guard fd >= 0 else {
            log("TCP server create failed")
            return false
        }

        var reuse: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &reuse, socklen_t(MemoryLayout<Int32>.size))

        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = tcpPort.bigEndian
        addr.sin_addr = in_addr(s_addr: INADDR_ANY)

        let bindResult = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }

        guard bindResult >= 0 else {
            log("TCP bind failed")
            close(fd)
            return false
        }

        guard listen(fd, 16) >= 0 else {
            log("TCP listen failed")
            close(fd)
            return false
        }

        tcpServerSocket = fd
        let source = DispatchSource.makeReadSource(fileDescriptor: fd, queue: queue)
        source.setEventHandler { [weak self] in
            self?.acceptTCP()
        }
        source.setCancelHandler {
            close(fd)
        }
        source.resume()
        tcpAcceptSource = source
        return true
    }

    private func startTimer() {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + .seconds(2), repeating: .seconds(2))
        timer.setEventHandler { [weak self] in
            self?.sendHello()
            self?.retryPendingAcks()
            self?.cleanupPeers()
        }
        timer.resume()
        self.timer = timer
    }

    private func sendHello() {
        guard let identity = identity else { return }
        let payload: [String: Any] = [
            "type": "hello",
            "userId": identity.userId,
            "orgId": identity.orgId,
            "level": identity.level,
            "nickname": identity.nickname,
            "device": "iOS",
            "tcpPort": Int(tcpPort),
            "timestampMs": Self.nowMs()
        ]

        guard let data = try? JSONSerialization.data(withJSONObject: payload, options: []) else {
            return
        }

        for host in broadcastTargets() {
            sendUDP(data: data, host: host)
        }
    }

    private func broadcastTargets() -> [String] {
        var hosts: Set<String> = ["255.255.255.255", "127.0.0.1"]
        var pointer: UnsafeMutablePointer<ifaddrs>?

        guard getifaddrs(&pointer) == 0, let start = pointer else {
            return Array(hosts)
        }
        defer { freeifaddrs(start) }

        var current: UnsafeMutablePointer<ifaddrs>? = start
        while let iface = current {
            defer { current = iface.pointee.ifa_next }

            guard let rawAddr = iface.pointee.ifa_addr,
                  rawAddr.pointee.sa_family == sa_family_t(AF_INET),
                  let rawMask = iface.pointee.ifa_netmask
            else {
                continue
            }

            let flags = iface.pointee.ifa_flags
            if (flags & UInt32(IFF_UP)) == 0 || (flags & UInt32(IFF_LOOPBACK)) != 0 {
                continue
            }

            let addr = withUnsafePointer(to: rawAddr.pointee) {
                $0.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr.s_addr }
            }
            let mask = withUnsafePointer(to: rawMask.pointee) {
                $0.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr.s_addr }
            }

            let hostOrderAddr = UInt32(bigEndian: addr)
            let hostOrderMask = UInt32(bigEndian: mask)
            var broadcast = in_addr(s_addr: (hostOrderAddr | ~hostOrderMask).bigEndian)

            var buffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            if inet_ntop(AF_INET, &broadcast, &buffer, socklen_t(INET_ADDRSTRLEN)) != nil {
                hosts.insert(String(cString: buffer))
            }
        }

        return Array(hosts)
    }

    private func sendUDP(data: Data, host: String) {
        guard udpSocket >= 0 else { return }

        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = discoveryPort.bigEndian
        _ = host.withCString { cString in
            inet_pton(AF_INET, cString, &addr.sin_addr)
        }

        _ = data.withUnsafeBytes { ptr in
            withUnsafePointer(to: &addr) { addrPtr in
                addrPtr.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    sendto(udpSocket, ptr.baseAddress, ptr.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }
    }

    private func readUDP() {
        var buffer = [UInt8](repeating: 0, count: 2048)
        var addr = sockaddr_in()
        var addrLen: socklen_t = socklen_t(MemoryLayout<sockaddr_in>.size)

        let count = withUnsafeMutablePointer(to: &addr) { ptr -> Int in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockPtr in
                recvfrom(udpSocket, &buffer, buffer.count, 0, sockPtr, &addrLen)
            }
        }

        guard count > 0 else { return }
        let data = Data(buffer.prefix(count))
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              (object["type"] as? String) == "hello",
              let userId = object["userId"] as? String,
              let identity = identity,
              userId != identity.userId,
              let tcpPort = (object["tcpPort"] as? Int).map(UInt16.init),
              let ip = ipAddress(from: addr)
        else {
            return
        }

        let nickname = ((object["nickname"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let peer = MeshPeer(
            userId: userId,
            nickname: nickname.isEmpty ? userId : nickname,
            host: ip,
            port: tcpPort,
            device: object["device"] as? String ?? "unknown",
            lastSeenMs: Self.nowMs()
        )

        peersByUserId[userId] = peer
        publish()
    }

    private func acceptTCP() {
        var clientAddr = sockaddr_in()
        var len: socklen_t = socklen_t(MemoryLayout<sockaddr_in>.size)
        let client = withUnsafeMutablePointer(to: &clientAddr) { ptr in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                accept(tcpServerSocket, $0, &len)
            }
        }

        guard client >= 0 else { return }
        tcpReadQueue.async { [weak self] in
            self?.readTCPClient(client)
        }
    }

    private func readTCPClient(_ fd: Int32) {
        defer { close(fd) }
        guard let lenData = readExact(fd: fd, size: 4) else { return }
        let length = lenData.withUnsafeBytes { ptr -> UInt32 in
            ptr.load(as: UInt32.self).bigEndian
        }
        guard length > 0, length <= 256 * 1024,
              let body = readExact(fd: fd, size: Int(length)),
              let envelope = MeshEnvelope.fromData(body)
        else {
            return
        }
        queue.async { [weak self] in
            self?.handleEnvelope(envelope)
        }
    }

    private func readExact(fd: Int32, size: Int) -> Data? {
        var data = Data(count: size)
        let readCount = data.withUnsafeMutableBytes { ptr -> Int in
            var offset = 0
            while offset < size {
                let chunk = read(fd, ptr.baseAddress!.advanced(by: offset), size - offset)
                if chunk <= 0 { return -1 }
                offset += chunk
            }
            return offset
        }
        return readCount == size ? data : nil
    }

    private func sendPacket(_ data: Data, host: String, port: UInt16) {
        for attempt in 1...3 {
            if sendPacketOnce(data, host: host, port: port) {
                return
            }
            if attempt < 3 {
                Thread.sleep(forTimeInterval: 0.05)
            }
        }
        log("send failed \(host):\(port)")
    }

    private func sendPacketOnce(_ data: Data, host: String, port: UInt16) -> Bool {
        let fd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
        guard fd >= 0 else { return false }
        defer { close(fd) }

        var noSigPipe: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &noSigPipe, socklen_t(MemoryLayout<Int32>.size))

        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = port.bigEndian
        _ = host.withCString { cString in
            inet_pton(AF_INET, cString, &addr.sin_addr)
        }

        let connected = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard connected >= 0 else {
            return false
        }

        var len = UInt32(data.count).bigEndian
        let header = Data(bytes: &len, count: 4)
        guard writeAll(fd: fd, data: header), writeAll(fd: fd, data: data) else {
            return false
        }
        return true
    }

    private func writeAll(fd: Int32, data: Data) -> Bool {
        var written = 0
        return data.withUnsafeBytes { ptr in
            guard let base = ptr.baseAddress else { return false }
            while written < data.count {
                let step = write(fd, base.advanced(by: written), data.count - written)
                if step <= 0 { return false }
                written += step
            }
            return true
        }
    }

    private func handleEnvelope(_ envelope: MeshEnvelope) {
        guard MeshSignature.verify(envelope: envelope) else {
            log("drop \(envelope.id.prefix(8)) invalid signature")
            return
        }

        switch envelope.type {
        case .ack:
            if let ackFor = envelope.ackForId {
                if let pending = pendingAcks.removeValue(forKey: ackFor) {
                    ackedCount += 1
                    if pending.contentKind == .fileChunk,
                       let peerId = pending.targetUserId,
                       let fileId = pending.fileId {
                        trackFileChunkAcked(peerId: peerId, fileId: fileId)
                    }
                    publish()
                }
            }
            return

        case .topology:
            if seenMessageIds.contains(envelope.id) { return }
            seenMessageIds.insert(envelope.id)
            if envelope.ttl > 0 {
                let forwarded = envelope.forwarding(ttl: envelope.ttl - 1, hopCount: envelope.hopCount + 1)
                sendEnvelope(forwarded, to: nil)
            }
            return

        case .chat:
            break
        }

        if seenMessageIds.contains(envelope.id) {
            if envelope.type == .chat {
                sendAck(for: envelope.id, to: envelope.senderUserId)
            }
            return
        }
        seenMessageIds.insert(envelope.id)

        guard let identity = identity else { return }
        let canRead = PolicyEngine.matches(
            user: UserDirectoryEntry(userId: identity.userId, orgId: identity.orgId, level: identity.level, active: true),
            policy: envelope.policy
        )

        var plainText: String?
        if canRead, let key = PolicyKeyService.resolveReadKey(identity: identity, keyID: envelope.keyId) {
            plainText = MeshCrypto.decryptWithKey(ivBase64: envelope.ivBase64, cipherTextBase64: envelope.cipherTextBase64, keyBytes: key)
        }

        let content = plainText.flatMap(ContentCodec.decode)
        let completion = processIncomingFile(content: content)
        let summary = messageSummary(content: content, fallback: plainText)

        let message = ReceivedMeshMessage(
            id: envelope.id,
            fromUserId: envelope.senderUserId,
            envelope: envelope,
            decryptedText: plainText,
            accessGranted: plainText != nil,
            content: content,
            contentSummary: summary,
            receivedFilePath: completion?.path,
            receivedFileName: completion?.name
        )
        incomingMessages = (incomingMessages + [message]).suffix(300)

        sendAck(for: envelope.id, to: envelope.senderUserId)

        if envelope.ttl > 0 {
            let forwarded = envelope.forwarding(ttl: envelope.ttl - 1, hopCount: envelope.hopCount + 1)
            sendEnvelope(forwarded, to: nil)
        }

        publish()
    }

    private func processIncomingFile(content: OutboundContent?) -> (name: String, path: String)? {
        guard let content else { return nil }

        switch content.kind {
        case .fileMeta:
            if let streamOp = content.payload["streamOp"] as? String,
               streamOp == "advertise" || streamOp == "remove" {
                return nil
            }
            guard let fileId = content.payload["fileId"] as? String else { return nil }
            var state = incomingFiles[fileId] ?? IncomingFileState(fileName: nil, expectedHash: nil, chunkTotal: nil, chunks: [:])
            if let fileName = content.payload["fileName"] as? String, !fileName.isEmpty {
                state.fileName = fileName
            }
            if let expectedHash = content.payload["sha256"] as? String, !expectedHash.isEmpty {
                state.expectedHash = expectedHash
            }
            incomingFiles[fileId] = state
            return nil

        case .fileChunk:
            guard let fileId = content.payload["fileId"] as? String,
                  let chunkIndex = content.payload["chunkIndex"] as? Int,
                  let chunkTotal = content.payload["chunkTotal"] as? Int,
                  let chunkBase64 = content.payload["chunkBase64"] as? String,
                  let chunkData = Data(base64Encoded: chunkBase64)
            else {
                return nil
            }

            var state = incomingFiles[fileId] ?? IncomingFileState(fileName: nil, expectedHash: nil, chunkTotal: chunkTotal, chunks: [:])
            state.chunkTotal = chunkTotal
            state.chunks[chunkIndex] = chunkData
            incomingFiles[fileId] = state

            guard state.chunks.count == chunkTotal else { return nil }
            var bytes = Data()
            let estimatedSize = state.chunks.values.reduce(0) { $0 + $1.count }
            bytes.reserveCapacity(estimatedSize)
            for index in 0..<chunkTotal {
                guard let chunk = state.chunks[index] else {
                    return nil
                }
                bytes.append(chunk)
            }
            let digest = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()

            if let expected = state.expectedHash, !expected.isEmpty, expected != digest {
                log("file hash mismatch: \(fileId)")
                return nil
            }

            let fileName = state.fileName ?? "file_\(fileId.prefix(6))"
            let path = saveReceivedFile(bytes: bytes, fileName: fileName)
            incomingFiles.removeValue(forKey: fileId)
            completedFiles[fileId] = (name: fileName, path: path)
            return completedFiles[fileId]

        case .voiceNoteMeta:
            guard let fileId = content.payload["fileId"] as? String else { return nil }
            var state = incomingFiles[fileId] ?? IncomingFileState(fileName: nil, expectedHash: nil, chunkTotal: nil, chunks: [:])
            if let name = content.payload["fileName"] as? String, !name.isEmpty {
                state.fileName = name
            }
            if let hash = content.payload["sha256"] as? String, !hash.isEmpty {
                state.expectedHash = hash
            }
            incomingFiles[fileId] = state
            return nil

        default:
            return nil
        }
    }

    private func saveReceivedFile(bytes: Data, fileName: String) -> String {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        let dir = documents.appendingPathComponent("received", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)

        let target = dir.appendingPathComponent(fileName)
        try? bytes.write(to: target, options: .atomic)
        return target.path
    }

    private func sendAck(for messageId: String, to userId: String) {
        guard let identity = identity,
              let signingIdentity = signingIdentity,
              var ack = MeshEnvelope.createAck(sender: identity, senderPublicKeyBase64: signingIdentity.publicKeyBase64, ackForId: messageId),
              let signature = signingIdentity.sign(ack.signingString())
        else {
            return
        }
        ack.signatureBase64 = signature
        sendEnvelope(ack, to: userId)
    }

    private func retryPendingAcks() {
        let now = Self.nowMs()
        let snapshot = pendingAcks

        for (id, pending) in snapshot {
            let retryIntervalMs: Int64 = pending.contentKind == .fileChunk ? 900 : 2500
            if now - pending.lastAttemptMs < retryIntervalMs {
                continue
            }
            if pending.retries >= pending.maxRetries {
                pendingAcks.removeValue(forKey: id)
                failedCount += 1
                if pending.contentKind == .fileChunk,
                   let peerId = pending.targetUserId,
                   let fileId = pending.fileId {
                    trackFileChunkFailed(peerId: peerId, fileId: fileId)
                }
                continue
            }

            var updated = pending
            updated.retries += 1
            updated.lastAttemptMs = now
            pendingAcks[id] = updated
            if pending.contentKind == .fileChunk,
               let peerId = pending.targetUserId,
               let fileId = pending.fileId {
                trackFileChunkRetried(peerId: peerId, fileId: fileId)
            }
            sendEnvelope(updated.envelope, to: updated.targetUserId)
        }
    }

    private func cleanupPeers() {
        let now = Self.nowMs()
        var next = peersByUserId.filter { _, peer in
            now - peer.lastSeenMs < 10_000
        }
        for (userId, resolved) in bonjourPeersByUserId {
            next[userId] = MeshPeer(
                userId: userId,
                nickname: resolved.nickname,
                host: resolved.host,
                port: resolved.port,
                device: resolved.device,
                lastSeenMs: now
            )
        }
        peersByUserId = next
        publish()
    }

    private func trackFileChunkEnqueued(peerId: String, fileId: String, chunkTotal: Int) {
        let now = Self.nowMs()
        var metrics = fileTransferDebugByPeerStore[peerId]
        if metrics?.fileId != fileId {
            metrics = FileTransferDebugMetrics(
                fileId: fileId,
                totalChunks: chunkTotal,
                pendingChunks: 0,
                ackedChunks: 0,
                retriedChunks: 0,
                failedChunks: 0,
                lastEventMs: now
            )
        }
        guard var metrics else { return }
        metrics.totalChunks = max(metrics.totalChunks, chunkTotal)
        metrics.pendingChunks += 1
        metrics.lastEventMs = now
        fileTransferDebugByPeerStore[peerId] = metrics
        publishFileTransferDebug()
    }

    private func trackFileChunkAcked(peerId: String, fileId: String) {
        let now = Self.nowMs()
        guard var metrics = fileTransferDebugByPeerStore[peerId], metrics.fileId == fileId else { return }
        metrics.pendingChunks = max(0, metrics.pendingChunks - 1)
        metrics.ackedChunks += 1
        metrics.lastEventMs = now
        fileTransferDebugByPeerStore[peerId] = metrics
        publishFileTransferDebug()
    }

    private func trackFileChunkRetried(peerId: String, fileId: String) {
        let now = Self.nowMs()
        guard var metrics = fileTransferDebugByPeerStore[peerId], metrics.fileId == fileId else { return }
        metrics.retriedChunks += 1
        metrics.lastEventMs = now
        fileTransferDebugByPeerStore[peerId] = metrics
        publishFileTransferDebug()
    }

    private func trackFileChunkFailed(peerId: String, fileId: String) {
        let now = Self.nowMs()
        guard var metrics = fileTransferDebugByPeerStore[peerId], metrics.fileId == fileId else { return }
        metrics.pendingChunks = max(0, metrics.pendingChunks - 1)
        metrics.failedChunks += 1
        metrics.lastEventMs = now
        fileTransferDebugByPeerStore[peerId] = metrics
        publishFileTransferDebug()
    }

    private func publishFileTransferDebug() {
        let snapshot = fileTransferDebugByPeerStore
        DispatchQueue.main.async {
            self.fileTransferDebugByPeer = snapshot
        }
    }

    private func publish() {
        DispatchQueue.main.async {
            self.peers = self.peersByUserId.values.sorted(by: { $0.userId < $1.userId })
            self.isRunning = self.udpSocket >= 0 && self.tcpServerSocket >= 0
        }
    }

    private func messageSummary(content: OutboundContent?, fallback: String?) -> String {
        guard let content else {
            return fallback ?? "Сообщение"
        }

        switch content.kind {
        case .text:
            return content.payload["text"] as? String ?? "Текст"
        case .fileMeta:
            return "Файл: \((content.payload["fileName"] as? String) ?? "unknown")"
        case .fileChunk:
            return "Чанк файла"
        case .voiceNoteMeta:
            return "Голосовое сообщение"
        case .videoNoteMeta:
            return "Видеосообщение"
        case .callSignal:
            let phase = content.payload["phase"] as? String ?? "signal"
            return "Звонок: \(phase)"
        case .audioPacket:
            return "Аудио поток"
        case .fileRepairRequest:
            return "Запрос восстановления файла"
        }
    }

    private func stopInternal() {
        DispatchQueue.main.async { [weak self] in
            self?.stopBonjour()
        }

        timer?.cancel()
        timer = nil

        udpReadSource?.cancel()
        udpReadSource = nil

        tcpAcceptSource?.cancel()
        tcpAcceptSource = nil

        if udpSocket >= 0 {
            close(udpSocket)
            udpSocket = -1
        }

        if tcpServerSocket >= 0 {
            close(tcpServerSocket)
            tcpServerSocket = -1
        }

        peersByUserId.removeAll()
        seenMessageIds.removeAll()
        pendingAcks.removeAll()
        fileTransferDebugByPeerStore.removeAll()
        incomingFiles.removeAll()
        completedFiles.removeAll()
        bonjourPeersByUserId.removeAll()
        serviceNameToUserId.removeAll()

        publishFileTransferDebug()
        publish()
        log("LAN mesh stopped")
    }

    private func log(_ line: String) {
        DispatchQueue.main.async {
            self.logs = (self.logs + [line]).suffix(120)
        }
    }

    private static func nowMs() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }

    private func ipAddress(from addr: sockaddr_in) -> String? {
        var address = addr.sin_addr
        var buffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        let result = inet_ntop(AF_INET, &address, &buffer, socklen_t(INET_ADDRSTRLEN))
        guard result != nil else { return nil }
        return String(cString: buffer)
    }

    private func startBonjour() {
        guard publishedService == nil, serviceBrowser == nil, let identity else { return }

        let txtRecord = NetService.data(fromTXTRecord: [
            "userId": Data(identity.userId.utf8),
            "orgId": Data(identity.orgId.utf8),
            "device": Data("iOS".utf8),
            "tcpPort": Data("\(tcpPort)".utf8),
            "nickname": Data(identity.nickname.utf8)
        ])

        let service = NetService(domain: bonjourDomain, type: bonjourServiceType, name: identity.userId, port: Int32(tcpPort))
        service.delegate = self
        service.setTXTRecord(txtRecord)
        service.publish()
        publishedService = service

        let browser = NetServiceBrowser()
        browser.delegate = self
        browser.searchForServices(ofType: bonjourServiceType, inDomain: bonjourDomain)
        serviceBrowser = browser
    }

    private func stopBonjour() {
        resolvingServices.values.forEach {
            $0.stop()
            $0.delegate = nil
        }
        resolvingServices.removeAll()

        serviceBrowser?.stop()
        serviceBrowser?.delegate = nil
        serviceBrowser = nil

        publishedService?.stop()
        publishedService?.delegate = nil
        publishedService = nil
    }

    private func extractIPv4(from data: Data) -> (host: String, port: UInt16)? {
        data.withUnsafeBytes { raw in
            guard let base = raw.baseAddress,
                  raw.count >= MemoryLayout<sockaddr_in>.size
            else {
                return nil
            }
            let sa = base.assumingMemoryBound(to: sockaddr.self)
            guard sa.pointee.sa_family == sa_family_t(AF_INET) else { return nil }
            let sin = base.assumingMemoryBound(to: sockaddr_in.self).pointee
            var address = sin.sin_addr
            var buffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            guard inet_ntop(AF_INET, &address, &buffer, socklen_t(INET_ADDRSTRLEN)) != nil else {
                return nil
            }
            return (String(cString: buffer), UInt16(bigEndian: sin.sin_port))
        }
    }
}

extension LanMeshService: NetServiceBrowserDelegate, NetServiceDelegate {
    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        guard let identity, service.name != identity.userId else { return }
        if resolvingServices[service.name] != nil { return }
        resolvingServices[service.name] = service
        service.delegate = self
        service.resolve(withTimeout: 3.0)
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService, moreComing: Bool) {
        resolvingServices.removeValue(forKey: service.name)
        guard let userId = serviceNameToUserId.removeValue(forKey: service.name) else { return }
        queue.async {
            self.bonjourPeersByUserId.removeValue(forKey: userId)
            self.peersByUserId.removeValue(forKey: userId)
            self.publish()
        }
    }

    func netServiceDidResolveAddress(_ sender: NetService) {
        resolvingServices.removeValue(forKey: sender.name)

        let txt = sender.txtRecordData().map { NetService.dictionary(fromTXTRecord: $0) } ?? [:]
        let userId = txt["userId"].flatMap { String(data: $0, encoding: .utf8) } ?? sender.name
        let deviceName = txt["device"].flatMap { String(data: $0, encoding: .utf8) } ?? "iOS"
        let nickname = txt["nickname"].flatMap { String(data: $0, encoding: .utf8) } ?? userId

        guard let identity, userId != identity.userId else { return }
        guard let addresses = sender.addresses else { return }
        guard let endpoint = addresses.compactMap({ self.extractIPv4(from: $0) }).first else { return }
        let resolvedPort = sender.port > 0 ? UInt16(sender.port) : endpoint.port

        serviceNameToUserId[sender.name] = userId
        queue.async {
            self.bonjourPeersByUserId[userId] = (nickname, endpoint.host, resolvedPort, "\(deviceName) (Bonjour)")
            self.peersByUserId[userId] = MeshPeer(
                userId: userId,
                nickname: nickname,
                host: endpoint.host,
                port: resolvedPort,
                device: "\(deviceName) (Bonjour)",
                lastSeenMs: Self.nowMs()
            )
            self.publish()
        }
    }

    func netService(_ sender: NetService, didNotResolve errorDict: [String: NSNumber]) {
        resolvingServices.removeValue(forKey: sender.name)
    }
}
