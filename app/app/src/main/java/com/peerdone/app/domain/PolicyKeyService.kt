package com.peerdone.app.domain

import java.security.MessageDigest

/**
 * Demo-grade key provisioning:
 * - не использует общий ключ на весь контент;
 * - выдает ключи на политику по org/level.
 *
 * Для production нужен внешний доверенный provisioning (QR/cert/CA/rotation).
 */
object PolicyKeyService {
    private const val MAX_LEVEL = 10
    private const val RECIPIENT_KEY_PREFIX = "recipient:"
    private const val RECIPIENT_MASTER = "peerdone-direct-msg-v1"

    private val orgMasters: Map<String, String> = mapOf(
        "org_city" to "master-city-v1-3bc9c42f",
        "org_med" to "master-med-v1-8aa0f90c",
        "org_factory" to "master-factory-v1-c913e4ab",
        "org_vol" to "master-vol-v1-22d8ac6b",
    )

    fun buildSendKey(sender: LocalIdentity, policy: AccessPolicy): Pair<String, ByteArray>? {
        val org = resolveTargetOrg(sender, policy) ?: return null
        val keyId = buildKeyId(org, policy)
        return keyId to deriveKey(keyId)
    }

    /** Ключ только для получателя: расшифровать может только peer с userId == recipientUserId. */
    fun buildSendKeyForRecipient(recipientUserId: String): Pair<String, ByteArray> {
        val norm = recipientUserId.substringBefore("|").trim()
        val keyId = "$RECIPIENT_KEY_PREFIX$norm"
        val keyBytes = MessageDigest.getInstance("SHA-256").digest("$RECIPIENT_MASTER|$norm".encodeToByteArray())
        return keyId to keyBytes
    }

    fun resolveReadKey(identity: LocalIdentity, keyId: String): ByteArray? {
        if (keyId.startsWith(RECIPIENT_KEY_PREFIX)) {
            val recipientId = keyId.removePrefix(RECIPIENT_KEY_PREFIX).substringBefore("|").trim()
            val myId = identity.userId.substringBefore("|").trim()
            if (recipientId != myId) return null
            return MessageDigest.getInstance("SHA-256").digest("$RECIPIENT_MASTER|$myId".encodeToByteArray())
        }
        if (!isKeyAllowedForIdentity(identity, keyId)) return null
        return deriveKey(keyId)
    }

    private fun resolveTargetOrg(sender: LocalIdentity, policy: AccessPolicy): String? {
        // В этой версии безопасно поддерживаем отправку только в org отправителя.
        if (policy.includeOrgs.isEmpty()) return sender.orgId
        return if (policy.includeOrgs.size == 1 && policy.includeOrgs.first() == sender.orgId) {
            sender.orgId
        } else {
            null
        }
    }

    private fun buildKeyId(orgId: String, policy: AccessPolicy): String {
        val max = policy.maxLevel
        val min = policy.minLevel
        return when {
            min != null && max != null -> "org:$orgId:range:$min:$max"
            max != null -> "org:$orgId:max:$max"
            min != null -> "org:$orgId:min:$min"
            else -> "org:$orgId:all"
        }
    }

    private fun isKeyAllowedForIdentity(identity: LocalIdentity, keyId: String): Boolean {
        val parts = keyId.split(':')
        if (parts.size < 3) return false
        val org = parts.getOrNull(1) ?: return false
        if (org != identity.orgId) return false

        val mode = parts.getOrNull(2) ?: return false
        return when (mode) {
            "all" -> true
            "max" -> {
                val max = parts.getOrNull(3)?.toIntOrNull() ?: return false
                identity.level <= max
            }
            "min" -> {
                val min = parts.getOrNull(3)?.toIntOrNull() ?: return false
                identity.level >= min
            }
            "range" -> {
                val min = parts.getOrNull(3)?.toIntOrNull() ?: return false
                val max = parts.getOrNull(4)?.toIntOrNull() ?: return false
                identity.level in min..max
            }
            else -> false
        }
    }

    private fun deriveKey(keyId: String): ByteArray {
        val parts = keyId.split(':')
        val orgId = parts.getOrNull(1) ?: error("Invalid key id: $keyId")
        val master = orgMasters[orgId] ?: error("Unknown org in key id: $keyId")
        val material = "$master|$keyId"
        return MessageDigest.getInstance("SHA-256").digest(material.encodeToByteArray())
    }
}

