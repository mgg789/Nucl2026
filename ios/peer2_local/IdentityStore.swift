import Foundation
import CryptoKit
import Security

final class IdentityStore {
    private let defaults = UserDefaults.standard
    private let identityKey = "peermesh.identity.v1"

    func loadOrCreateIdentity() -> LocalIdentity {
        if let data = defaults.data(forKey: identityKey),
           let identity = try? JSONDecoder().decode(LocalIdentity.self, from: data) {
            return identity
        }

        let generated = LocalIdentity(
            userId: "ios_\(UUID().uuidString.prefix(8))",
            orgId: "org_city",
            level: 3,
            nickname: "iPhone"
        )
        saveIdentity(generated)
        return generated
    }

    func saveIdentity(_ identity: LocalIdentity) {
        if let data = try? JSONEncoder().encode(identity) {
            defaults.set(data, forKey: identityKey)
        }
    }

    func loadOrCreateSigningIdentity(userId: String) -> SignedIdentity {
        let tag = "com.peer.mesh.key.\(userId)"
        if let existing = keyFromKeychain(tag: tag) {
            return SignedIdentity(privateKey: existing, publicKeyBase64: existing.publicKey.derRepresentation.base64EncodedString())
        }

        let generated = P256.Signing.PrivateKey()
        saveToKeychain(generated, tag: tag)
        return SignedIdentity(privateKey: generated, publicKeyBase64: generated.publicKey.derRepresentation.base64EncodedString())
    }

    private func keyFromKeychain(tag: String) -> P256.Signing.PrivateKey? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.peer.mesh.keys",
            kSecAttrAccount as String: tag,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]

        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess,
              let data = result as? Data,
              let key = try? P256.Signing.PrivateKey(rawRepresentation: data)
        else {
            return nil
        }
        return key
    }

    private func saveToKeychain(_ key: P256.Signing.PrivateKey, tag: String) {
        let data = key.rawRepresentation
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.peer.mesh.keys",
            kSecAttrAccount as String: tag,
            kSecValueData as String: data
        ]

        SecItemDelete(query as CFDictionary)
        SecItemAdd(query as CFDictionary, nil)
    }
}
