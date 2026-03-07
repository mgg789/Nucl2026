package com.peerdone.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleDirectoryTest {

    @Test
    fun users_has_expected_count() {
        assertEquals(6, SampleDirectory.users.size)
    }

    @Test
    fun users_contains_expected_orgs() {
        val orgs = SampleDirectory.users.map { it.orgId }.toSet()
        assertTrue(orgs.contains("org_city"))
        assertTrue(orgs.contains("org_med"))
        assertTrue(orgs.contains("org_factory"))
        assertTrue(orgs.contains("org_vol"))
    }

    @Test
    fun identities_matches_users_count() {
        assertEquals(SampleDirectory.users.size, SampleDirectory.identities.size)
    }

    @Test
    fun identities_mapped_from_users() {
        val user = SampleDirectory.users.first()
        val identity = SampleDirectory.identities.first { it.userId == user.userId }
        assertEquals(user.userId, identity.userId)
        assertEquals(user.orgId, identity.orgId)
        assertEquals(user.level, identity.level)
    }
}
