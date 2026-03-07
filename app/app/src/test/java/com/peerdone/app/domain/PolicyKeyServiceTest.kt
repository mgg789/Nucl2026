package com.peerdone.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyKeyServiceTest {

    private val senderCity = LocalIdentity(userId = "user1", orgId = "org_city", level = 2)
    private val senderMed = LocalIdentity(userId = "user2", orgId = "org_med", level = 1)

    @Test
    fun buildSendKey_returns_key_for_single_org_policy_matching_sender() {
        val policy = AccessPolicy(includeOrgs = setOf("org_city"), maxLevel = 5)
        val result = PolicyKeyService.buildSendKey(senderCity, policy)
        assertNotNull(result)
        assertEquals(32, result!!.second.size)
        assertTrue(result.first.startsWith("org:org_city:"))
        assertEquals(32, result.second.size) // SHA-256 = 32 bytes
    }

    @Test
    fun buildSendKey_returns_null_when_policy_org_differs_from_sender() {
        val policy = AccessPolicy(includeOrgs = setOf("org_med"), maxLevel = 5)
        val result = PolicyKeyService.buildSendKey(senderCity, policy)
        assertNull(result)
    }

    @Test
    fun buildSendKey_keyId_format_max_only() {
        val policy = AccessPolicy(includeOrgs = setOf("org_city"), maxLevel = 3)
        val result = PolicyKeyService.buildSendKey(senderCity, policy)
        assertNotNull(result)
        assertTrue(result!!.first == "org:org_city:max:3")
    }

    @Test
    fun buildSendKey_keyId_format_min_only() {
        val policy = AccessPolicy(includeOrgs = setOf("org_city"), minLevel = 2)
        val result = PolicyKeyService.buildSendKey(senderCity, policy)
        assertNotNull(result)
        assertTrue(result!!.first == "org:org_city:min:2")
    }

    @Test
    fun buildSendKey_keyId_format_range() {
        val policy = AccessPolicy(includeOrgs = setOf("org_city"), minLevel = 1, maxLevel = 3)
        val result = PolicyKeyService.buildSendKey(senderCity, policy)
        assertNotNull(result)
        assertTrue(result!!.first == "org:org_city:range:1:3")
    }

    @Test
    fun buildSendKey_keyId_format_all_when_no_level_limits() {
        val policy = AccessPolicy(includeOrgs = setOf("org_city"))
        val result = PolicyKeyService.buildSendKey(senderCity, policy)
        assertNotNull(result)
        assertTrue(result!!.first == "org:org_city:all")
    }

    @Test
    fun buildSendKeyForRecipient_returns_32_bytes() {
        val (keyId, keyBytes) = PolicyKeyService.buildSendKeyForRecipient("alice")
        assertTrue(keyId.startsWith("recipient:"))
        assertTrue(keyId.endsWith("alice"))
        assertEquals(32, keyBytes.size)
    }

    @Test
    fun buildSendKeyForRecipient_normalizes_userId_before_pipe() {
        val (keyId1, bytes1) = PolicyKeyService.buildSendKeyForRecipient("alice")
        val (keyId2, bytes2) = PolicyKeyService.buildSendKeyForRecipient("alice|extra")
        assertEquals(keyId1, keyId2)
        assertTrue(bytes1.contentEquals(bytes2))
    }

    @Test
    fun resolveReadKey_recipient_key_returns_key_for_matching_identity() {
        val identity = LocalIdentity(userId = "bob", orgId = "org_city", level = 1)
        val (keyId, _) = PolicyKeyService.buildSendKeyForRecipient("bob")
        val keyBytes = PolicyKeyService.resolveReadKey(identity, keyId)
        assertNotNull(keyBytes)
        assertEquals(32, keyBytes!!.size)
    }

    @Test
    fun resolveReadKey_recipient_key_returns_null_for_different_identity() {
        val identity = LocalIdentity(userId = "alice", orgId = "org_city", level = 1)
        val (keyId, _) = PolicyKeyService.buildSendKeyForRecipient("bob")
        val keyBytes = PolicyKeyService.resolveReadKey(identity, keyId)
        assertNull(keyBytes)
    }

    @Test
    fun resolveReadKey_org_key_returns_key_when_identity_matches_org_and_level() {
        val policy = AccessPolicy(includeOrgs = setOf("org_city"), maxLevel = 3)
        val (keyId, _) = PolicyKeyService.buildSendKey(senderCity, policy)!!
        val keyBytes = PolicyKeyService.resolveReadKey(senderCity, keyId)
        assertNotNull(keyBytes)
    }

    @Test
    fun resolveReadKey_org_key_returns_null_when_wrong_org() {
        val policy = AccessPolicy(includeOrgs = setOf("org_city"), maxLevel = 3)
        val (keyId, _) = PolicyKeyService.buildSendKey(senderCity, policy)!!
        val keyBytes = PolicyKeyService.resolveReadKey(senderMed, keyId)
        assertNull(keyBytes)
    }

    @Test
    fun resolveReadKey_returns_null_for_invalid_keyId_format() {
        val keyBytes = PolicyKeyService.resolveReadKey(senderCity, "invalid:key")
        assertNull(keyBytes)
    }

    @Test
    fun resolveReadKey_returns_null_for_unknown_org_in_keyId() {
        val keyBytes = PolicyKeyService.resolveReadKey(senderCity, "org:unknown_org:all")
        assertNull(keyBytes)
    }
}
