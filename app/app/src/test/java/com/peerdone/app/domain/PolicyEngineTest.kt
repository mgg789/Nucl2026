package com.peerdone.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyEngineTest {

    @Test
    fun matches_allows_when_includeOrgs_contains_user_org_and_level_ok() {
        val user = UserDirectoryEntry(userId = "u1", orgId = "org_city", level = 2)
        val policy = AccessPolicy(includeOrgs = setOf("org_city"), maxLevel = 3)
        assertTrue(PolicyEngine.matches(user, policy))
    }

    @Test
    fun matches_denies_when_user_org_not_in_includeOrgs() {
        val user = UserDirectoryEntry(userId = "u2", orgId = "org_med", level = 2)
        val policy = AccessPolicy(includeOrgs = setOf("org_city"), maxLevel = 3)
        assertFalse(PolicyEngine.matches(user, policy))
    }

    @Test
    fun matches_denies_when_user_level_exceeds_maxLevel() {
        val user = UserDirectoryEntry(userId = "u3", orgId = "org_city", level = 5)
        val policy = AccessPolicy(includeOrgs = setOf("org_city"), maxLevel = 3)
        assertFalse(PolicyEngine.matches(user, policy))
    }

    @Test
    fun matches_denies_inactive_user() {
        val user = UserDirectoryEntry(userId = "u1", orgId = "org_city", level = 1, active = false)
        val policy = AccessPolicy(includeOrgs = setOf("org_city"))
        assertFalse(PolicyEngine.matches(user, policy))
    }

    @Test
    fun matches_allows_when_includeOrgs_empty_and_no_other_restrictions() {
        val user = UserDirectoryEntry(userId = "u1", orgId = "org_any", level = 10)
        val policy = AccessPolicy()
        assertTrue(PolicyEngine.matches(user, policy))
    }

    @Test
    fun matches_denies_when_user_org_in_excludeOrgs() {
        val user = UserDirectoryEntry(userId = "u1", orgId = "org_blocked", level = 1)
        val policy = AccessPolicy(excludeOrgs = setOf("org_blocked"))
        assertFalse(PolicyEngine.matches(user, policy))
    }

    @Test
    fun matches_allows_when_minLevel_satisfied() {
        val user = UserDirectoryEntry(userId = "u1", orgId = "org_city", level = 3)
        val policy = AccessPolicy(includeOrgs = setOf("org_city"), minLevel = 2)
        assertTrue(PolicyEngine.matches(user, policy))
    }

    @Test
    fun matches_denies_when_level_below_minLevel() {
        val user = UserDirectoryEntry(userId = "u1", orgId = "org_city", level = 1)
        val policy = AccessPolicy(includeOrgs = setOf("org_city"), minLevel = 2)
        assertFalse(PolicyEngine.matches(user, policy))
    }

    @Test
    fun matches_allows_when_level_in_range() {
        val user = UserDirectoryEntry(userId = "u1", orgId = "org_city", level = 2)
        val policy = AccessPolicy(includeOrgs = setOf("org_city"), minLevel = 1, maxLevel = 3)
        assertTrue(PolicyEngine.matches(user, policy))
    }

    @Test
    fun matches_denies_when_level_below_range() {
        val user = UserDirectoryEntry(userId = "u1", orgId = "org_city", level = 0)
        val policy = AccessPolicy(includeOrgs = setOf("org_city"), minLevel = 1, maxLevel = 3)
        assertFalse(PolicyEngine.matches(user, policy))
    }

    @Test
    fun matches_denies_when_level_above_range() {
        val user = UserDirectoryEntry(userId = "u1", orgId = "org_city", level = 4)
        val policy = AccessPolicy(includeOrgs = setOf("org_city"), minLevel = 1, maxLevel = 3)
        assertFalse(PolicyEngine.matches(user, policy))
    }

    @Test
    fun resolveRecipients_returns_only_matching_users() {
        val directory = listOf(
            UserDirectoryEntry(userId = "a", orgId = "org_city", level = 1),
            UserDirectoryEntry(userId = "b", orgId = "org_med", level = 1),
            UserDirectoryEntry(userId = "c", orgId = "org_city", level = 5),
            UserDirectoryEntry(userId = "d", orgId = "org_city", level = 2),
        )
        val policy = AccessPolicy(includeOrgs = setOf("org_city"), maxLevel = 3)
        val result = PolicyEngine.resolveRecipients(policy, directory)
        assertEquals(2, result.size)
        assertTrue(result.any { it.userId == "a" })
        assertTrue(result.any { it.userId == "d" })
    }

    @Test
    fun resolveRecipients_returns_empty_when_none_match() {
        val directory = listOf(
            UserDirectoryEntry(userId = "a", orgId = "org_med", level = 1),
        )
        val policy = AccessPolicy(includeOrgs = setOf("org_city"))
        val result = PolicyEngine.resolveRecipients(policy, directory)
        assertTrue(result.isEmpty())
    }

    @Test
    fun resolveRecipients_excludes_inactive() {
        val directory = listOf(
            UserDirectoryEntry(userId = "a", orgId = "org_city", level = 1, active = false),
        )
        val policy = AccessPolicy(includeOrgs = setOf("org_city"))
        val result = PolicyEngine.resolveRecipients(policy, directory)
        assertTrue(result.isEmpty())
    }
}
