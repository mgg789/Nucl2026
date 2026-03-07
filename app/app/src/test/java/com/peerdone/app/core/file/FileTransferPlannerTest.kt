package com.peerdone.app.core.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
class FileTransferPlannerTest {

    @Test
    fun plan_single_chunk_when_bytes_smaller_than_chunk_size() {
        val bytes = ByteArray(100) { it.toByte() }
        val planned = FileTransferPlanner.plan(
            fileName = "tiny.bin",
            mimeType = "application/octet-stream",
            bytes = bytes,
            chunkSizeBytes = 1024,
        )
        assertEquals(1, planned.chunks.size)
        assertEquals(1, planned.chunks.first().chunkTotal)
        assertEquals(0, planned.chunks.first().chunkIndex)
        assertEquals(100L, planned.meta.totalBytes)
        assertEquals("tiny.bin", planned.meta.fileName)
        assertEquals(64, planned.meta.sha256.length)
    }

    @Test
    fun plan_multiple_chunks_correct_indices() {
        val bytes = ByteArray(25_000) { (it % 251).toByte() }
        val planned = FileTransferPlanner.plan(
            fileName = "mid.bin",
            mimeType = "application/octet-stream",
            bytes = bytes,
            chunkSizeBytes = 8 * 1024,
        )
        assertTrue(planned.chunks.size >= 3)
        planned.chunks.forEachIndexed { idx, chunk ->
            assertEquals(idx, chunk.chunkIndex)
            assertEquals(planned.chunks.size, chunk.chunkTotal)
            assertEquals(planned.fileId, chunk.fileId)
        }
    }

    @Test
    fun plan_sha256_hex_length_64() {
        val bytes = byteArrayOf(1, 2, 3)
        val planned = FileTransferPlanner.plan("x", "application/octet-stream", bytes)
        assertEquals(64, planned.meta.sha256.length)
        assertTrue(planned.meta.sha256.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun plan_fileId_is_uuid_format() {
        val planned = FileTransferPlanner.plan("a", "b", byteArrayOf(1))
        assertEquals(36, planned.fileId.length)
        assertTrue(planned.fileId[8] == '-')
        assertTrue(planned.fileId[13] == '-')
    }

    @Test
    fun plan_empty_file_one_chunk() {
        val planned = FileTransferPlanner.plan(
            fileName = "empty.txt",
            mimeType = "text/plain",
            bytes = ByteArray(0),
            chunkSizeBytes = 1024,
        )
        assertEquals(1, planned.chunks.size)
        assertEquals(0L, planned.meta.totalBytes)
        assertEquals(0, planned.chunks.first().chunkIndex)
        assertEquals(1, planned.chunks.first().chunkTotal)
    }

    @Test(expected = IllegalArgumentException::class)
    fun plan_throws_when_chunkSize_zero() {
        FileTransferPlanner.plan("x", "y", byteArrayOf(1), chunkSizeBytes = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun plan_throws_when_chunkSize_negative() {
        FileTransferPlanner.plan("x", "y", byteArrayOf(1), chunkSizeBytes = -1)
    }

    @Test
    fun plan_last_chunk_smaller_than_chunk_size() {
        val chunkSize = 100
        val bytes = ByteArray(250) { it.toByte() }
        val planned = FileTransferPlanner.plan("x", "y", bytes, chunkSizeBytes = chunkSize)
        assertEquals(3, planned.chunks.size)
        assertEquals(250L, planned.meta.totalBytes)
        assertTrue(planned.chunks[0].chunkBase64.length >= planned.chunks[2].chunkBase64.length)
    }
}
