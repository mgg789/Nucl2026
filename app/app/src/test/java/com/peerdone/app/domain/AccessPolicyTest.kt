package com.peerdone.app.domain

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessPolicyTest {

    @Test
    fun isAll_true_when_all_fields_null_or_empty() {
        val policy = AccessPolicy()
        assertTrue(policy.isAll)
    }

    @Test
    fun isAll_false_when_maxLevel_set() {
        assertFalse(AccessPolicy(maxLevel = 5).isAll)
    }

    @Test
    fun isAll_false_when_minLevel_set() {
        assertFalse(AccessPolicy(minLevel = 1).isAll)
    }

    @Test
    fun isAll_false_when_includeOrgs_not_empty() {
        assertFalse(AccessPolicy(includeOrgs = setOf("org_a")).isAll)
    }

    @Test
    fun isAll_false_when_excludeOrgs_not_empty() {
        assertFalse(AccessPolicy(excludeOrgs = setOf("org_a")).isAll)
    }

    @Test
    fun toJson_roundtrip_with_fromJson() {
        val policy = AccessPolicy(
            maxLevel = 3,
            minLevel = 1,
            includeOrgs = setOf("org_city", "org_med"),
            excludeOrgs = setOf("org_blocked"),
        )
        val json = policy.toJson()
        val restored = AccessPolicy.fromJson(json)
        assertEquals(policy.maxLevel, restored.maxLevel)
        assertEquals(policy.minLevel, restored.minLevel)
        assertEquals(policy.includeOrgs, restored.includeOrgs)
        assertEquals(policy.excludeOrgs, restored.excludeOrgs)
    }

    @Test
    fun fromJson_handles_null_maxLevel_minLevel() {
        val json = JSONObject()
            .put("maxLevel", JSONObject.NULL)
            .put("minLevel", JSONObject.NULL)
            .put("includeOrgs", JSONArray())
            .put("excludeOrgs", JSONArray())
        val policy = AccessPolicy.fromJson(json)
        assertEquals(null, policy.maxLevel)
        assertEquals(null, policy.minLevel)
        assertTrue(policy.includeOrgs.isEmpty())
        assertTrue(policy.excludeOrgs.isEmpty())
    }

    @Test
    fun signingJSON_is_deterministic() {
        val policy = AccessPolicy(maxLevel = 2, includeOrgs = setOf("org_a"))
        val s1 = policy.signingJSON()
        val s2 = policy.signingJSON()
        assertEquals(s1, s2)
    }

    @Test
    fun signingJSON_escapes_special_chars_in_orgs() {
        val policy = AccessPolicy(includeOrgs = setOf("org\"quote"))
        val s = policy.signingJSON()
        assertTrue(s.contains("org\\\"quote"))
    }
}
