package com.peerdone.app.domain

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshEnvelopeTest {

    private val sender = LocalIdentity("user1", "org_city", 2)

    @Test
    fun AccessPolicy_fromJson_toJson_roundtrip() {
        val policy = AccessPolicy(
            maxLevel = 3,
            minLevel = 1,
            includeOrgs = setOf("org_a", "org_b"),
            excludeOrgs = setOf("org_x"),
        )
        val json = policy.toJson()
        val restored = AccessPolicy.fromJson(json)
        assertEquals(policy.maxLevel, restored.maxLevel)
        assertEquals(policy.minLevel, restored.minLevel)
        assertEquals(policy.includeOrgs, restored.includeOrgs)
        assertEquals(policy.excludeOrgs, restored.excludeOrgs)
    }

    @Test
    fun MeshEnvelope_forForwarding_updates_ttl_and_hopCount() {
        val enc = MeshCrypto.encryptControl("payload")
        val envelope = MeshEnvelope(
            type = MeshMessageType.CHAT,
            senderUserId = sender.userId,
            senderOrgId = sender.orgId,
            senderLevel = sender.level,
            senderPublicKeyBase64 = "dGVzdA==",
            keyId = "control:v1",
            ttl = 3,
            hopCount = 0,
            ivBase64 = enc.ivBase64,
            cipherTextBase64 = enc.cipherTextBase64,
            policy = AccessPolicy(),
            signatureBase64 = "sig",
        )
        val forwarded = envelope.forForwarding(newTtl = 2, newHopCount = 1)
        assertEquals(2, forwarded.ttl)
        assertEquals(1, forwarded.hopCount)
        assertEquals(envelope.id, forwarded.id)
    }

    @Test
    fun MeshEnvelope_signingString_is_deterministic() {
        val enc = MeshCrypto.encryptControl("x")
        val envelope = MeshEnvelope(
            type = MeshMessageType.ACK,
            ackForId = "id1",
            senderUserId = "u1",
            senderOrgId = "org1",
            senderLevel = 1,
            senderPublicKeyBase64 = "pk",
            keyId = "k1",
            timestampMs = 12345L,
            ivBase64 = enc.ivBase64,
            cipherTextBase64 = enc.cipherTextBase64,
            policy = AccessPolicy(),
            signatureBase64 = "s",
        )
        val s1 = envelope.signingString()
        val s2 = envelope.signingString()
        assertEquals(s1, s2)
        assertTrue(s1.contains("u1"))
        assertTrue(s1.contains("ACK"))
    }

    @Test
    fun MeshMessageType_enum_values() {
        assertEquals(3, MeshMessageType.entries.size)
        assertEquals(MeshMessageType.CHAT, MeshMessageType.valueOf("CHAT"))
    }
}
