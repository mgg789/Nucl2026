package com.example.app.domain

import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

data class MeshEnvelope(
    val id: String = UUID.randomUUID().toString(),
    val type: MeshMessageType,
    val ackForId: String? = null,
    val senderUserId: String,
    val senderOrgId: String,
    val senderLevel: Int,
    val senderPublicKeyBase64: String,
    val keyId: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val ttl: Int = 3,
    val hopCount: Int = 0,
    val ivBase64: String,
    val cipherTextBase64: String,
    val policy: AccessPolicy,
    val signatureBase64: String,
) {
    fun toJsonBytes(): ByteArray {
        val json = JSONObject()
            .put("id", id)
            .put("type", type.name)
            .put("ackForId", ackForId)
            .put("senderUserId", senderUserId)
            .put("senderOrgId", senderOrgId)
            .put("senderLevel", senderLevel)
            .put("senderPublicKeyBase64", senderPublicKeyBase64)
            .put("keyId", keyId)
            .put("timestampMs", timestampMs)
            .put("ttl", ttl)
            .put("hopCount", hopCount)
            .put("ivBase64", ivBase64)
            .put("cipherTextBase64", cipherTextBase64)
            .put("policy", policy.toJson())
            .put("signatureBase64", signatureBase64)
        return json.toString().encodeToByteArray()
    }

    // В подпись не включаем ttl/hopCount, чтобы ретранслятор мог форвардить пакет без перезаписи подписи.
    fun signingString(): String = buildString {
        append(id)
        append('|')
        append(type.name)
        append('|')
        append(ackForId.orEmpty())
        append('|')
        append(senderUserId)
        append('|')
        append(senderOrgId)
        append('|')
        append(senderLevel)
        append('|')
        append(senderPublicKeyBase64)
        append('|')
        append(keyId)
        append('|')
        append(timestampMs)
        append('|')
        append(ivBase64)
        append('|')
        append(cipherTextBase64)
        append('|')
        append(policy.toJson().toString())
    }

    fun forForwarding(newTtl: Int, newHopCount: Int): MeshEnvelope = copy(
        ttl = newTtl,
        hopCount = newHopCount,
    )

    companion object {
        fun fromJsonBytes(bytes: ByteArray): MeshEnvelope {
            val raw = bytes.decodeToString()
            val json = JSONObject(raw)
            return MeshEnvelope(
                id = json.getString("id"),
                type = MeshMessageType.valueOf(json.getString("type")),
                ackForId = json.optString("ackForId").ifBlank { null },
                senderUserId = json.getString("senderUserId"),
                senderOrgId = json.getString("senderOrgId"),
                senderLevel = json.getInt("senderLevel"),
                senderPublicKeyBase64 = json.getString("senderPublicKeyBase64"),
                keyId = json.getString("keyId"),
                timestampMs = json.getLong("timestampMs"),
                ttl = json.optInt("ttl", 3),
                hopCount = json.optInt("hopCount", 0),
                ivBase64 = json.getString("ivBase64"),
                cipherTextBase64 = json.getString("cipherTextBase64"),
                policy = AccessPolicy.fromJson(json.getJSONObject("policy")),
                signatureBase64 = json.getString("signatureBase64"),
            )
        }

        fun createChat(
            sender: LocalIdentity,
            senderPublicKeyBase64: String,
            keyId: String,
            keyBytes: ByteArray,
            messageText: String,
            policy: AccessPolicy,
            ttl: Int = 3,
        ): MeshEnvelope {
            val encrypted = MeshCrypto.encryptWithKey(messageText, keyBytes)
            return MeshEnvelope(
                type = MeshMessageType.CHAT,
                senderUserId = sender.userId,
                senderOrgId = sender.orgId,
                senderLevel = sender.level,
                senderPublicKeyBase64 = senderPublicKeyBase64,
                keyId = keyId,
                ttl = ttl,
                hopCount = 0,
                ivBase64 = encrypted.ivBase64,
                cipherTextBase64 = encrypted.cipherTextBase64,
                policy = policy,
                signatureBase64 = "",
            )
        }

        fun createAck(
            sender: LocalIdentity,
            senderPublicKeyBase64: String,
            ackForId: String,
        ): MeshEnvelope {
            val encrypted = MeshCrypto.encryptControl("ack")
            return MeshEnvelope(
                type = MeshMessageType.ACK,
                ackForId = ackForId,
                senderUserId = sender.userId,
                senderOrgId = sender.orgId,
                senderLevel = sender.level,
                senderPublicKeyBase64 = senderPublicKeyBase64,
                keyId = MeshCrypto.CONTROL_KEY_ID,
                ttl = 1,
                hopCount = 0,
                ivBase64 = encrypted.ivBase64,
                cipherTextBase64 = encrypted.cipherTextBase64,
                policy = AccessPolicy(),
                signatureBase64 = "",
            )
        }

        fun createTopology(
            sender: LocalIdentity,
            senderPublicKeyBase64: String,
            topologyJson: String,
            ttl: Int = 2,
        ): MeshEnvelope {
            val encrypted = MeshCrypto.encryptControl(topologyJson)
            return MeshEnvelope(
                type = MeshMessageType.TOPOLOGY,
                senderUserId = sender.userId,
                senderOrgId = sender.orgId,
                senderLevel = sender.level,
                senderPublicKeyBase64 = senderPublicKeyBase64,
                keyId = MeshCrypto.CONTROL_KEY_ID,
                ttl = ttl,
                hopCount = 0,
                ivBase64 = encrypted.ivBase64,
                cipherTextBase64 = encrypted.cipherTextBase64,
                policy = AccessPolicy(),
                signatureBase64 = "",
            )
        }
    }
}

enum class MeshMessageType {
    CHAT,
    ACK,
    TOPOLOGY,
}

data class DecryptedPayload(
    val ivBase64: String,
    val cipherTextBase64: String,
)

@OptIn(ExperimentalEncodingApi::class)
object MeshCrypto {
    const val CONTROL_KEY_ID = "control:v1"

    private val controlKeyBytes: ByteArray = MessageDigest
        .getInstance("SHA-256")
        .digest("nucl2026-mesh-demo-shared-key".encodeToByteArray())
    fun encryptControl(plainText: String): DecryptedPayload = encryptWithKey(plainText, controlKeyBytes)

    fun decryptControl(ivBase64: String, cipherTextBase64: String): String =
        decryptWithKey(ivBase64, cipherTextBase64, controlKeyBytes)

    fun encryptWithKey(plainText: String, keyBytes: ByteArray): DecryptedPayload {
        val iv = ByteArray(12)
        java.security.SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plainText.encodeToByteArray())
        return DecryptedPayload(
            ivBase64 = Base64.encode(iv),
            cipherTextBase64 = Base64.encode(encrypted),
        )
    }

    fun decryptWithKey(ivBase64: String, cipherTextBase64: String, keyBytes: ByteArray): String {
        val iv = Base64.decode(ivBase64)
        val encrypted = Base64.decode(cipherTextBase64)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted).decodeToString()
    }
}

@OptIn(ExperimentalEncodingApi::class)
object MeshSignature {
    fun verify(envelope: MeshEnvelope): Boolean {
        if (envelope.signatureBase64.isBlank()) return false
        return runCatching {
            val pubKey = decodePublicKey(envelope.senderPublicKeyBase64)
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initVerify(pubKey)
            signature.update(envelope.signingString().encodeToByteArray())
            signature.verify(Base64.decode(envelope.signatureBase64))
        }.getOrElse { false }
    }

    private fun decodePublicKey(base64: String): PublicKey {
        val bytes = Base64.decode(base64)
        val spec = X509EncodedKeySpec(bytes)
        return KeyFactory.getInstance("EC").generatePublic(spec)
    }
}

fun AccessPolicy.toJson(): JSONObject {
    return JSONObject()
        .put("maxLevel", maxLevel)
        .put("minLevel", minLevel)
        .put("includeOrgs", JSONArray(includeOrgs.toList()))
        .put("excludeOrgs", JSONArray(excludeOrgs.toList()))
}

fun AccessPolicy.Companion.fromJson(json: JSONObject): AccessPolicy {
    val include = mutableSetOf<String>()
    val exclude = mutableSetOf<String>()

    val includeArr = json.optJSONArray("includeOrgs") ?: JSONArray()
    val excludeArr = json.optJSONArray("excludeOrgs") ?: JSONArray()

    for (i in 0 until includeArr.length()) {
        include += includeArr.getString(i)
    }
    for (i in 0 until excludeArr.length()) {
        exclude += excludeArr.getString(i)
    }

    return AccessPolicy(
        maxLevel = if (json.isNull("maxLevel")) null else json.optInt("maxLevel"),
        minLevel = if (json.isNull("minLevel")) null else json.optInt("minLevel"),
        includeOrgs = include,
        excludeOrgs = exclude,
    )
}

data class LocalIdentity(
    val userId: String,
    val orgId: String,
    val level: Int,
)

object SampleDirectory {
    val users: List<UserDirectoryEntry> = listOf(
        UserDirectoryEntry(userId = "hq_ivan", orgId = "org_city", level = 0),
        UserDirectoryEntry(userId = "med_alina", orgId = "org_med", level = 1),
        UserDirectoryEntry(userId = "sec_oleg", orgId = "org_city", level = 1),
        UserDirectoryEntry(userId = "team_katya", orgId = "org_factory", level = 2),
        UserDirectoryEntry(userId = "staff_misha", orgId = "org_factory", level = 3),
        UserDirectoryEntry(userId = "volunteer_anna", orgId = "org_vol", level = 4),
    )

    val identities: List<LocalIdentity> = users.map {
        LocalIdentity(
            userId = it.userId,
            orgId = it.orgId,
            level = it.level,
        )
    }
}

