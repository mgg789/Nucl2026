import Foundation
import CryptoKit

struct LocalIdentity: Codable, Equatable {
    var userId: String
    var orgId: String
    var level: Int
    var nickname: String
}

struct UserDirectoryEntry {
    let userId: String
    let orgId: String
    let level: Int
    let active: Bool
}

struct AccessPolicy: Codable, Equatable {
    var maxLevel: Int?
    var minLevel: Int?
    var includeOrgs: [String]
    var excludeOrgs: [String]

    init(maxLevel: Int? = nil, minLevel: Int? = nil, includeOrgs: [String] = [], excludeOrgs: [String] = []) {
        self.maxLevel = maxLevel
        self.minLevel = minLevel
        self.includeOrgs = includeOrgs
        self.excludeOrgs = excludeOrgs
    }

    var isAll: Bool {
        maxLevel == nil && minLevel == nil && includeOrgs.isEmpty && excludeOrgs.isEmpty
    }

    func signingJSON() -> String {
        let includes = includeOrgs.map { "\"\(Self.escape($0))\"" }.joined(separator: ",")
        let excludes = excludeOrgs.map { "\"\(Self.escape($0))\"" }.joined(separator: ",")
        let maxPart = maxLevel.map(String.init) ?? "null"
        let minPart = minLevel.map(String.init) ?? "null"
        return "{\"maxLevel\":\(maxPart),\"minLevel\":\(minPart),\"includeOrgs\":[\(includes)],\"excludeOrgs\":[\(excludes)]}"
    }

    func toJSONObject() -> [String: Any] {
        [
            "maxLevel": maxLevel as Any,
            "minLevel": minLevel as Any,
            "includeOrgs": includeOrgs,
            "excludeOrgs": excludeOrgs
        ]
    }

    static func fromJSONObject(_ object: [String: Any]) -> AccessPolicy {
        let includeAny = object["includeOrgs"] as? [Any]
        let include = includeAny?.compactMap { $0 as? String } ?? (object["includeOrgs"] as? [String]) ?? []
        let excludeAny = object["excludeOrgs"] as? [Any]
        let exclude = excludeAny?.compactMap { $0 as? String } ?? (object["excludeOrgs"] as? [String]) ?? []
        let max = (object["maxLevel"] as? NSNumber)?.intValue ?? object["maxLevel"] as? Int
        let min = (object["minLevel"] as? NSNumber)?.intValue ?? object["minLevel"] as? Int
        return AccessPolicy(maxLevel: max, minLevel: min, includeOrgs: include, excludeOrgs: exclude)
    }

    private static func escape(_ value: String) -> String {
        value
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")
            .replacingOccurrences(of: "\n", with: "\\n")
            .replacingOccurrences(of: "\r", with: "\\r")
            .replacingOccurrences(of: "\t", with: "\\t")
    }
}

enum PolicyEngine {
    static func matches(user: UserDirectoryEntry, policy: AccessPolicy) -> Bool {
        if !user.active { return false }
        if !policy.includeOrgs.isEmpty && !policy.includeOrgs.contains(user.orgId) { return false }
        if policy.excludeOrgs.contains(user.orgId) { return false }
        if let max = policy.maxLevel, user.level > max { return false }
        if let min = policy.minLevel, user.level < min { return false }
        return true
    }
}

enum MeshMessageType: String {
    case chat = "CHAT"
    case ack = "ACK"
    case topology = "TOPOLOGY"
}

struct MeshEnvelope {
    var id: String = UUID().uuidString
    var type: MeshMessageType
    var ackForId: String?
    var senderUserId: String
    var senderOrgId: String
    var senderLevel: Int
    var senderPublicKeyBase64: String
    var keyId: String
    /** Кому адресовано (1:1); nil = broadcast. Совместимость с Android. */
    var recipientUserId: String?
    var timestampMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    var ttl: Int = 3
    var hopCount: Int = 0
    var ivBase64: String
    var cipherTextBase64: String
    var policy: AccessPolicy
    var signatureBase64: String

    func signingString() -> String {
        [
            id,
            type.rawValue,
            ackForId ?? "",
            senderUserId,
            senderOrgId,
            String(senderLevel),
            senderPublicKeyBase64,
            keyId,
            String(timestampMs),
            ivBase64,
            cipherTextBase64,
            policy.signingJSON()
        ].joined(separator: "|")
    }

    func forwarding(ttl newTTL: Int, hopCount newHopCount: Int) -> MeshEnvelope {
        var copy = self
        copy.ttl = max(0, newTTL)
        copy.hopCount = newHopCount
        return copy
    }

    func toData() -> Data? {
        var object: [String: Any] = [
            "id": id,
            "type": type.rawValue,
            "senderUserId": senderUserId,
            "senderOrgId": senderOrgId,
            "senderLevel": senderLevel,
            "senderPublicKeyBase64": senderPublicKeyBase64,
            "keyId": keyId,
            "timestampMs": timestampMs,
            "ttl": ttl,
            "hopCount": hopCount,
            "ivBase64": ivBase64,
            "cipherTextBase64": cipherTextBase64,
            "policy": policy.toJSONObject(),
            "signatureBase64": signatureBase64,
        ]
        object["ackForId"] = ackForId
        object["recipientUserId"] = recipientUserId ?? ""
        return try? JSONSerialization.data(withJSONObject: object, options: [])
    }

    static func fromData(_ data: Data) -> MeshEnvelope? {
        guard let raw = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let id = raw["id"] as? String,
              let typeString = raw["type"] as? String,
              let type = MeshMessageType(rawValue: typeString),
              let senderUserId = raw["senderUserId"] as? String,
              let senderOrgId = raw["senderOrgId"] as? String,
              let senderPublicKeyBase64 = raw["senderPublicKeyBase64"] as? String,
              let keyId = raw["keyId"] as? String,
              let ivBase64 = raw["ivBase64"] as? String,
              let cipherTextBase64 = raw["cipherTextBase64"] as? String,
              let policyObject = raw["policy"] as? [String: Any],
              let signatureBase64 = raw["signatureBase64"] as? String
        else {
            return nil
        }
        let senderLevel = (raw["senderLevel"] as? NSNumber)?.intValue ?? (raw["senderLevel"] as? Int) ?? 0
        let timestampMs = (raw["timestampMs"] as? NSNumber)?.int64Value ?? (raw["timestampMs"] as? Int64) ?? 0
        let ttl = (raw["ttl"] as? NSNumber)?.intValue ?? (raw["ttl"] as? Int) ?? 3
        let hopCount = (raw["hopCount"] as? NSNumber)?.intValue ?? (raw["hopCount"] as? Int) ?? 0

        let recipient = (raw["recipientUserId"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        return MeshEnvelope(
            id: id,
            type: type,
            ackForId: raw["ackForId"] as? String,
            senderUserId: senderUserId,
            senderOrgId: senderOrgId,
            senderLevel: senderLevel,
            senderPublicKeyBase64: senderPublicKeyBase64,
            keyId: keyId,
            recipientUserId: (recipient?.isEmpty ?? true) ? nil : recipient,
            timestampMs: timestampMs,
            ttl: ttl,
            hopCount: hopCount,
            ivBase64: ivBase64,
            cipherTextBase64: cipherTextBase64,
            policy: AccessPolicy.fromJSONObject(policyObject),
            signatureBase64: signatureBase64
        )
    }

    static func createChat(sender: LocalIdentity, senderPublicKeyBase64: String, keyId: String, keyBytes: Data, messageText: String, policy: AccessPolicy, ttl: Int = 3, recipientUserId: String? = nil) -> MeshEnvelope? {
        guard let encrypted = MeshCrypto.encryptWithKey(messageText: messageText, keyBytes: keyBytes) else {
            return nil
        }
        return MeshEnvelope(
            type: .chat,
            ackForId: nil,
            senderUserId: sender.userId,
            senderOrgId: sender.orgId,
            senderLevel: sender.level,
            senderPublicKeyBase64: senderPublicKeyBase64,
            keyId: keyId,
            recipientUserId: recipientUserId,
            ttl: ttl,
            hopCount: 0,
            ivBase64: encrypted.ivBase64,
            cipherTextBase64: encrypted.cipherTextBase64,
            policy: policy,
            signatureBase64: ""
        )
    }

    static func createTopology(sender: LocalIdentity, senderPublicKeyBase64: String, topologyJson: String, ttl: Int = 2) -> MeshEnvelope? {
        guard let encrypted = MeshCrypto.encryptControl(plainText: topologyJson) else { return nil }
        return MeshEnvelope(
            type: .topology,
            ackForId: nil,
            senderUserId: sender.userId,
            senderOrgId: sender.orgId,
            senderLevel: sender.level,
            senderPublicKeyBase64: senderPublicKeyBase64,
            keyId: MeshCrypto.controlKeyID,
            recipientUserId: nil,
            ttl: ttl,
            hopCount: 0,
            ivBase64: encrypted.ivBase64,
            cipherTextBase64: encrypted.cipherTextBase64,
            policy: AccessPolicy(),
            signatureBase64: ""
        )
    }

    static func createAck(sender: LocalIdentity, senderPublicKeyBase64: String, ackForId: String) -> MeshEnvelope? {
        guard let control = MeshCrypto.encryptControl(plainText: "ack") else {
            return nil
        }
        return MeshEnvelope(
            type: .ack,
            ackForId: ackForId,
            senderUserId: sender.userId,
            senderOrgId: sender.orgId,
            senderLevel: sender.level,
            senderPublicKeyBase64: senderPublicKeyBase64,
            keyId: MeshCrypto.controlKeyID,
            recipientUserId: nil,
            ttl: 1,
            hopCount: 0,
            ivBase64: control.ivBase64,
            cipherTextBase64: control.cipherTextBase64,
            policy: AccessPolicy(),
            signatureBase64: ""
        )
    }
}

enum ContentKind: String {
    case text = "TEXT"
    case fileMeta = "FILE_META"
    case fileChunk = "FILE_CHUNK"
    case fileRepairRequest = "FILE_REPAIR_REQUEST"
    case voiceNoteMeta = "VOICE_NOTE_META"
    case videoNoteMeta = "VIDEO_NOTE_META"
    case callSignal = "CALL_SIGNAL"
    case audioPacket = "AUDIO_PACKET"
}

struct OutboundContent {
    let kind: ContentKind
    let payload: [String: Any]

    static func text(_ text: String) -> OutboundContent {
        OutboundContent(kind: .text, payload: ["text": text])
    }

    static func fileMeta(fileId: String = UUID().uuidString, fileName: String, totalBytes: Int64, mimeType: String, sha256: String) -> OutboundContent {
        OutboundContent(kind: .fileMeta, payload: [
            "fileId": fileId,
            "fileName": fileName,
            "totalBytes": totalBytes,
            "mimeType": mimeType,
            "sha256": sha256
        ])
    }

    static func fileChunk(fileId: String, chunkIndex: Int, chunkTotal: Int, chunkBase64: String) -> OutboundContent {
        OutboundContent(kind: .fileChunk, payload: [
            "fileId": fileId,
            "chunkIndex": chunkIndex,
            "chunkTotal": chunkTotal,
            "chunkBase64": chunkBase64
        ])
    }

    static func callSignal(callId: String, phase: String, sdpOrIce: String) -> OutboundContent {
        OutboundContent(kind: .callSignal, payload: [
            "callId": callId,
            "phase": phase,
            "sdpOrIce": sdpOrIce
        ])
    }

    static func audioPacket(callId: String, sequenceNumber: Int64, timestampMs: Int64, audioDataBase64: String, codec: String = "pcm_16bit", sampleRate: Int = 16000) -> OutboundContent {
        OutboundContent(kind: .audioPacket, payload: [
            "callId": callId,
            "sequenceNumber": sequenceNumber,
            "timestampMs": timestampMs,
            "audioDataBase64": audioDataBase64,
            "codec": codec,
            "sampleRate": sampleRate
        ])
    }
}

enum ContentCodec {
    static func encode(_ content: OutboundContent) -> String {
        var object = content.payload
        object["kind"] = content.kind.rawValue
        let data = (try? JSONSerialization.data(withJSONObject: object, options: [])) ?? Data("{}".utf8)
        return String(data: data, encoding: .utf8) ?? "{}"
    }

    static func decode(_ raw: String) -> OutboundContent? {
        guard let data = raw.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let kindRaw = object["kind"] as? String,
              let kind = ContentKind(rawValue: kindRaw)
        else {
            return nil
        }
        var payload = object
        payload.removeValue(forKey: "kind")
        return OutboundContent(kind: kind, payload: payload)
    }
}

struct DecryptedPayload {
    let ivBase64: String
    let cipherTextBase64: String
}

enum MeshCrypto {
    static let controlKeyID = "control:v1"

    private static let controlKeyBytes: Data = {
        let input = Data("nucl2026-mesh-demo-shared-key".utf8)
        let digest = SHA256.hash(data: input)
        return Data(digest)
    }()

    static func encryptControl(plainText: String) -> DecryptedPayload? {
        encryptWithKey(messageText: plainText, keyBytes: controlKeyBytes)
    }

    static func decryptControl(ivBase64: String, cipherTextBase64: String) -> String? {
        decryptWithKey(ivBase64: ivBase64, cipherTextBase64: cipherTextBase64, keyBytes: controlKeyBytes)
    }

    static func encryptWithKey(messageText: String, keyBytes: Data) -> DecryptedPayload? {
        guard keyBytes.count == 32 else { return nil }
        let key = SymmetricKey(data: keyBytes)
        let iv = randomData(count: 12)
        guard let nonce = try? AES.GCM.Nonce(data: iv),
              let sealed = try? AES.GCM.seal(Data(messageText.utf8), using: key, nonce: nonce)
        else {
            return nil
        }

        let encrypted = sealed.ciphertext + sealed.tag
        return DecryptedPayload(
            ivBase64: iv.base64EncodedString(),
            cipherTextBase64: encrypted.base64EncodedString()
        )
    }

    static func decryptWithKey(ivBase64: String, cipherTextBase64: String, keyBytes: Data) -> String? {
        guard keyBytes.count == 32,
              let ivData = Data(base64Encoded: ivBase64),
              let encrypted = Data(base64Encoded: cipherTextBase64),
              encrypted.count >= 16,
              let nonce = try? AES.GCM.Nonce(data: ivData)
        else {
            return nil
        }

        let cipher = encrypted.prefix(encrypted.count - 16)
        let tag = encrypted.suffix(16)
        guard let box = try? AES.GCM.SealedBox(nonce: nonce, ciphertext: cipher, tag: tag),
              let plain = try? AES.GCM.open(box, using: SymmetricKey(data: keyBytes))
        else {
            return nil
        }
        return String(data: plain, encoding: .utf8)
    }

    private static func randomData(count: Int) -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        let result = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
        if result == errSecSuccess {
            return Data(bytes)
        }
        return Data((0..<count).map { _ in UInt8.random(in: 0...255) })
    }
}

enum PolicyKeyService {
    private static let recipientKeyPrefix = "recipient:"
    private static let recipientMaster = "peerdone-direct-msg-v1"
    private static let orgMasters: [String: String] = [
        "org_city": "master-city-v1-3bc9c42f",
        "org_med": "master-med-v1-8aa0f90c",
        "org_factory": "master-factory-v1-c913e4ab",
        "org_vol": "master-vol-v1-22d8ac6b"
    ]

    static func buildSendKey(sender: LocalIdentity, policy: AccessPolicy) -> (String, Data)? {
        guard let org = resolveTargetOrg(sender: sender, policy: policy) else {
            return nil
        }
        let keyID = buildKeyId(orgId: org, policy: policy)
        return (keyID, deriveKey(keyID: keyID))
    }

    /// Ключ только для получателя (1:1); совместимость с Android.
    static func buildSendKeyForRecipient(recipientUserId: String) -> (String, Data) {
        let norm = recipientUserId.split(separator: "|").first.map(String.init)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? recipientUserId
        let keyId = "\(recipientKeyPrefix)\(norm)"
        let material = "\(recipientMaster)|\(norm)"
        let digest = SHA256.hash(data: Data(material.utf8))
        return (keyId, Data(digest))
    }

    static func resolveReadKey(identity: LocalIdentity, keyID: String) -> Data? {
        if keyID.hasPrefix(recipientKeyPrefix) {
            let recipientId = String(keyID.dropFirst(recipientKeyPrefix.count)).split(separator: "|").first.map(String.init)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let myId = identity.userId.split(separator: "|").first.map(String.init)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? identity.userId
            guard recipientId == myId else { return nil }
            let material = "\(recipientMaster)|\(myId)"
            let digest = SHA256.hash(data: Data(material.utf8))
            return Data(digest)
        }
        guard isKeyAllowed(identity: identity, keyID: keyID) else {
            return nil
        }
        return deriveKey(keyID: keyID)
    }

    private static func resolveTargetOrg(sender: LocalIdentity, policy: AccessPolicy) -> String? {
        if policy.includeOrgs.isEmpty {
            return sender.orgId
        }
        if policy.includeOrgs.count == 1, policy.includeOrgs.first == sender.orgId {
            return sender.orgId
        }
        return nil
    }

    private static func buildKeyId(orgId: String, policy: AccessPolicy) -> String {
        if let min = policy.minLevel, let max = policy.maxLevel {
            return "org:\(orgId):range:\(min):\(max)"
        }
        if let max = policy.maxLevel {
            return "org:\(orgId):max:\(max)"
        }
        if let min = policy.minLevel {
            return "org:\(orgId):min:\(min)"
        }
        return "org:\(orgId):all"
    }

    private static func isKeyAllowed(identity: LocalIdentity, keyID: String) -> Bool {
        let parts = keyID.split(separator: ":").map(String.init)
        guard parts.count >= 3 else { return false }
        guard parts[1] == identity.orgId else { return false }

        switch parts[2] {
        case "all":
            return true
        case "max":
            guard parts.count >= 4, let max = Int(parts[3]) else { return false }
            return identity.level <= max
        case "min":
            guard parts.count >= 4, let min = Int(parts[3]) else { return false }
            return identity.level >= min
        case "range":
            guard parts.count >= 5, let min = Int(parts[3]), let max = Int(parts[4]) else { return false }
            return identity.level >= min && identity.level <= max
        default:
            return false
        }
    }

    private static func deriveKey(keyID: String) -> Data {
        let parts = keyID.split(separator: ":").map(String.init)
        let org = parts.count > 1 ? parts[1] : ""
        let master = orgMasters[org] ?? ""
        let material = Data("\(master)|\(keyID)".utf8)
        let digest = SHA256.hash(data: material)
        return Data(digest)
    }
}

enum MeshSignature {
    static func verify(envelope: MeshEnvelope) -> Bool {
        guard let sigData = Data(base64Encoded: envelope.signatureBase64),
              let keyData = Data(base64Encoded: envelope.senderPublicKeyBase64),
              let publicKey = try? P256.Signing.PublicKey(derRepresentation: keyData),
              let signature = try? P256.Signing.ECDSASignature(derRepresentation: sigData)
        else {
            return false
        }

        let signingData = Data(envelope.signingString().utf8)
        return publicKey.isValidSignature(signature, for: signingData)
    }
}

struct SignedIdentity {
    let privateKey: P256.Signing.PrivateKey
    let publicKeyBase64: String

    func sign(_ message: String) -> String? {
        guard let signature = try? privateKey.signature(for: Data(message.utf8)) else {
            return nil
        }
        return signature.derRepresentation.base64EncodedString()
    }
}
