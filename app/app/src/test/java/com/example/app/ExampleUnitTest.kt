package com.example.app

import com.example.app.core.file.FileTransferPlanner
import com.example.app.domain.AccessPolicy
import com.example.app.domain.PolicyEngine
import com.example.app.domain.UserDirectoryEntry
import org.junit.Test

import org.junit.Assert.*

class ExampleUnitTest {
    @Test
    fun policy_engine_filters_by_org_and_level() {
        val userAllowed = UserDirectoryEntry(userId = "u1", orgId = "org_city", level = 2)
        val userDeniedOrg = UserDirectoryEntry(userId = "u2", orgId = "org_med", level = 2)
        val userDeniedLevel = UserDirectoryEntry(userId = "u3", orgId = "org_city", level = 5)
        val policy = AccessPolicy(
            includeOrgs = setOf("org_city"),
            maxLevel = 3,
        )

        assertTrue(PolicyEngine.matches(userAllowed, policy))
        assertFalse(PolicyEngine.matches(userDeniedOrg, policy))
        assertFalse(PolicyEngine.matches(userDeniedLevel, policy))
    }

    @Test
    fun file_transfer_planner_splits_and_hashes() {
        val bytes = ByteArray(70_000) { (it % 251).toByte() }
        val planned = FileTransferPlanner.plan(
            fileName = "demo.bin",
            mimeType = "application/octet-stream",
            bytes = bytes,
            chunkSizeBytes = 16 * 1024,
        )

        assertEquals("demo.bin", planned.meta.fileName)
        assertEquals(bytes.size.toLong(), planned.meta.totalBytes)
        assertTrue(planned.chunks.isNotEmpty())
        assertEquals(planned.chunks.size, planned.chunks.first().chunkTotal)
        assertEquals(0, planned.chunks.first().chunkIndex)
        assertEquals(planned.chunks.size - 1, planned.chunks.last().chunkIndex)
        assertEquals(64, planned.meta.sha256.length)
    }
}