package com.peerdone.app.core.file

import com.peerdone.app.core.message.OutboundContent
import java.security.MessageDigest
import java.util.UUID
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

data class PlannedFileTransfer(
    val fileId: String,
    val meta: OutboundContent.FileMeta,
    val chunks: List<OutboundContent.FileChunk>,
)

@OptIn(ExperimentalEncodingApi::class)
object FileTransferPlanner {
    /** Размер чанка под лимит Nearby Connections (32 KB на весь payload с envelope). */
    private const val DEFAULT_CHUNK_SIZE = 8 * 1024

    fun plan(
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        chunkSizeBytes: Int = DEFAULT_CHUNK_SIZE,
    ): PlannedFileTransfer {
        require(chunkSizeBytes > 0) { "chunkSizeBytes must be > 0" }

        val fileId = UUID.randomUUID().toString()
        val sha = sha256Hex(bytes)
        val meta = OutboundContent.FileMeta(
            fileId = fileId,
            fileName = fileName,
            totalBytes = bytes.size.toLong(),
            mimeType = mimeType,
            sha256 = sha,
        )

        val total = (bytes.size + chunkSizeBytes - 1) / chunkSizeBytes
        val chunks = (0 until total).map { idx ->
            val start = idx * chunkSizeBytes
            val end = minOf(bytes.size, start + chunkSizeBytes)
            val part = bytes.copyOfRange(start, end)
            OutboundContent.FileChunk(
                fileId = fileId,
                chunkIndex = idx,
                chunkTotal = total,
                chunkBase64 = Base64.encode(part),
            )
        }
        return PlannedFileTransfer(fileId = fileId, meta = meta, chunks = chunks)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { b -> "%02x".format(b) }
    }
}

