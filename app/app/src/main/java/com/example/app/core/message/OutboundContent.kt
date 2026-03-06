package com.example.app.core.message

import org.json.JSONObject
import java.util.UUID

enum class ContentKind {
    TEXT,
    FILE_META,
    FILE_CHUNK,
    VOICE_NOTE_META,
    VIDEO_NOTE_META,
    CALL_SIGNAL,
}

sealed class OutboundContent(
    val kind: ContentKind,
) {
    data class Text(
        val text: String,
    ) : OutboundContent(ContentKind.TEXT)

    data class FileMeta(
        val fileId: String = UUID.randomUUID().toString(),
        val fileName: String,
        val totalBytes: Long,
        val mimeType: String,
        val sha256: String,
    ) : OutboundContent(ContentKind.FILE_META)

    data class FileChunk(
        val fileId: String,
        val chunkIndex: Int,
        val chunkTotal: Int,
        val chunkBase64: String,
    ) : OutboundContent(ContentKind.FILE_CHUNK)

    data class VoiceNoteMeta(
        val fileId: String,
        val durationMs: Long,
        val codec: String,
    ) : OutboundContent(ContentKind.VOICE_NOTE_META)

    data class VideoNoteMeta(
        val fileId: String,
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val codec: String,
    ) : OutboundContent(ContentKind.VIDEO_NOTE_META)

    data class CallSignal(
        val callId: String,
        val phase: String,
        val sdpOrIce: String,
    ) : OutboundContent(ContentKind.CALL_SIGNAL)
}

object ContentCodec {
    fun encode(content: OutboundContent): String {
        val json = JSONObject().put("kind", content.kind.name)
        when (content) {
            is OutboundContent.Text -> json.put("text", content.text)
            is OutboundContent.FileMeta -> json
                .put("fileId", content.fileId)
                .put("fileName", content.fileName)
                .put("totalBytes", content.totalBytes)
                .put("mimeType", content.mimeType)
                .put("sha256", content.sha256)
            is OutboundContent.FileChunk -> json
                .put("fileId", content.fileId)
                .put("chunkIndex", content.chunkIndex)
                .put("chunkTotal", content.chunkTotal)
                .put("chunkBase64", content.chunkBase64)
            is OutboundContent.VoiceNoteMeta -> json
                .put("fileId", content.fileId)
                .put("durationMs", content.durationMs)
                .put("codec", content.codec)
            is OutboundContent.VideoNoteMeta -> json
                .put("fileId", content.fileId)
                .put("durationMs", content.durationMs)
                .put("width", content.width)
                .put("height", content.height)
                .put("codec", content.codec)
            is OutboundContent.CallSignal -> json
                .put("callId", content.callId)
                .put("phase", content.phase)
                .put("sdpOrIce", content.sdpOrIce)
        }
        return json.toString()
    }

    fun decode(raw: String): OutboundContent? {
        return runCatching {
            val json = JSONObject(raw)
            val kind = ContentKind.valueOf(json.getString("kind"))
            when (kind) {
                ContentKind.TEXT -> OutboundContent.Text(
                    text = json.getString("text"),
                )
                ContentKind.FILE_META -> OutboundContent.FileMeta(
                    fileId = json.getString("fileId"),
                    fileName = json.getString("fileName"),
                    totalBytes = json.getLong("totalBytes"),
                    mimeType = json.getString("mimeType"),
                    sha256 = json.getString("sha256"),
                )
                ContentKind.FILE_CHUNK -> OutboundContent.FileChunk(
                    fileId = json.getString("fileId"),
                    chunkIndex = json.getInt("chunkIndex"),
                    chunkTotal = json.getInt("chunkTotal"),
                    chunkBase64 = json.getString("chunkBase64"),
                )
                ContentKind.VOICE_NOTE_META -> OutboundContent.VoiceNoteMeta(
                    fileId = json.getString("fileId"),
                    durationMs = json.getLong("durationMs"),
                    codec = json.getString("codec"),
                )
                ContentKind.VIDEO_NOTE_META -> OutboundContent.VideoNoteMeta(
                    fileId = json.getString("fileId"),
                    durationMs = json.getLong("durationMs"),
                    width = json.getInt("width"),
                    height = json.getInt("height"),
                    codec = json.getString("codec"),
                )
                ContentKind.CALL_SIGNAL -> OutboundContent.CallSignal(
                    callId = json.getString("callId"),
                    phase = json.getString("phase"),
                    sdpOrIce = json.getString("sdpOrIce"),
                )
            }
        }.getOrNull()
    }
}

