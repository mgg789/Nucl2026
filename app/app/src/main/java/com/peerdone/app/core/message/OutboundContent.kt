package com.peerdone.app.core.message

import org.json.JSONObject
import java.util.UUID

enum class ContentKind {
    TEXT,
    FILE_META,
    FILE_CHUNK,
    FILE_REPAIR_REQUEST,
    VOICE_NOTE_META,
    VIDEO_NOTE_META,
    CALL_SIGNAL,
    AUDIO_PACKET,
    HISTORY_REQUEST,
    HISTORY_RESPONSE,
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

    data class FileRepairRequest(
        val fileId: String,
        val missingIndices: List<Int>,
    ) : OutboundContent(ContentKind.FILE_REPAIR_REQUEST)

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

    data class AudioPacket(
        val callId: String,
        val sequenceNumber: Long,
        val timestampMs: Long,
        val audioDataBase64: String,
        val codec: String = "pcm_16bit",
        val sampleRate: Int = 16000,
    ) : OutboundContent(ContentKind.AUDIO_PACKET)

    /** Запрос истории чата у узла (отправитель — наш peerId, получатель пришлёт свои сообщения для этого чата). */
    data class HistoryRequest(
        val requestId: String,
    ) : OutboundContent(ContentKind.HISTORY_REQUEST)

    /** Ответ на запрос истории: список сообщений для чата с отправителем этого пакета. */
    data class HistoryResponse(
        val requestId: String,
        val messages: List<HistoryResponseItem>,
    ) : OutboundContent(ContentKind.HISTORY_RESPONSE)
}

data class HistoryResponseItem(
    val id: String,
    val text: String,
    val timestampMs: Long,
    val isOutgoing: Boolean,
    val type: String,
    val fileName: String? = null,
    val filePath: String? = null,
    val voiceFileId: String? = null,
)

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
            is OutboundContent.FileRepairRequest -> json
                .put("fileId", content.fileId)
                .put("missingIndices", content.missingIndices.joinToString(","))
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
            is OutboundContent.AudioPacket -> json
                .put("callId", content.callId)
                .put("sequenceNumber", content.sequenceNumber)
                .put("timestampMs", content.timestampMs)
                .put("audioDataBase64", content.audioDataBase64)
                .put("codec", content.codec)
                .put("sampleRate", content.sampleRate)
            is OutboundContent.HistoryRequest -> json.put("requestId", content.requestId)
            is OutboundContent.HistoryResponse -> {
                json.put("requestId", content.requestId)
                val arr = org.json.JSONArray()
                content.messages.forEach { m ->
                    arr.put(org.json.JSONObject().apply {
                        put("id", m.id)
                        put("text", m.text)
                        put("timestampMs", m.timestampMs)
                        put("isOutgoing", m.isOutgoing)
                        put("type", m.type)
                        put("fileName", m.fileName ?: "")
                        put("filePath", m.filePath ?: "")
                        put("voiceFileId", m.voiceFileId ?: "")
                    })
                }
                json.put("messages", arr)
            }
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
                ContentKind.FILE_REPAIR_REQUEST -> OutboundContent.FileRepairRequest(
                    fileId = json.getString("fileId"),
                    missingIndices = json.optString("missingIndices")
                        .split(',')
                        .mapNotNull { it.trim().toIntOrNull() },
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
                ContentKind.AUDIO_PACKET -> OutboundContent.AudioPacket(
                    callId = json.getString("callId"),
                    sequenceNumber = json.getLong("sequenceNumber"),
                    timestampMs = json.getLong("timestampMs"),
                    audioDataBase64 = json.getString("audioDataBase64"),
                    codec = json.optString("codec", "pcm_16bit"),
                    sampleRate = json.optInt("sampleRate", 16000),
                )
                ContentKind.HISTORY_REQUEST -> OutboundContent.HistoryRequest(
                    requestId = json.getString("requestId"),
                )
                ContentKind.HISTORY_RESPONSE -> {
                    val arr = json.getJSONArray("messages")
                    val list = (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        HistoryResponseItem(
                            id = o.getString("id"),
                            text = o.optString("text", ""),
                            timestampMs = o.optLong("timestampMs", 0L),
                            isOutgoing = o.optBoolean("isOutgoing", false),
                            type = o.optString("type", "TEXT"),
                            fileName = o.optString("fileName").takeIf { it.isNotEmpty() },
                            filePath = o.optString("filePath").takeIf { it.isNotEmpty() },
                            voiceFileId = o.optString("voiceFileId").takeIf { it.isNotEmpty() },
                        )
                    }
                    OutboundContent.HistoryResponse(requestId = json.getString("requestId"), messages = list)
                }
            }
        }.getOrNull()
    }
}

