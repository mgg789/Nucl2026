package com.peerdone.app.core.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ContentCodecTest {

    @Test
    fun encode_decode_Text_roundtrip() {
        val original = OutboundContent.Text("Hello, mesh!")
        val encoded = ContentCodec.encode(original)
        assertNotNull(encoded)
        val decoded = ContentCodec.decode(encoded)
        assertNotNull(decoded)
        assert(decoded is OutboundContent.Text)
        assertEquals("Hello, mesh!", (decoded as OutboundContent.Text).text)
    }

    @Test
    fun encode_decode_FileMeta_roundtrip() {
        val original = OutboundContent.FileMeta(
            fileId = "f1",
            fileName = "doc.pdf",
            totalBytes = 1024L,
            mimeType = "application/pdf",
            sha256 = "a".repeat(64),
        )
        val decoded = ContentCodec.decode(ContentCodec.encode(original))
        assert(decoded is OutboundContent.FileMeta)
        val d = decoded as OutboundContent.FileMeta
        assertEquals(original.fileId, d.fileId)
        assertEquals(original.fileName, d.fileName)
        assertEquals(original.totalBytes, d.totalBytes)
        assertEquals(original.mimeType, d.mimeType)
        assertEquals(original.sha256, d.sha256)
    }

    @Test
    fun encode_decode_FileChunk_roundtrip() {
        val original = OutboundContent.FileChunk(
            fileId = "f1",
            chunkIndex = 2,
            chunkTotal = 5,
            chunkBase64 = "YWJjZGVm",
        )
        val decoded = ContentCodec.decode(ContentCodec.encode(original))
        assert(decoded is OutboundContent.FileChunk)
        val d = decoded as OutboundContent.FileChunk
        assertEquals(original.fileId, d.fileId)
        assertEquals(original.chunkIndex, d.chunkIndex)
        assertEquals(original.chunkTotal, d.chunkTotal)
        assertEquals(original.chunkBase64, d.chunkBase64)
    }

    @Test
    fun encode_decode_FileRepairRequest_roundtrip() {
        val original = OutboundContent.FileRepairRequest(
            fileId = "f1",
            missingIndices = listOf(1, 3, 5),
        )
        val decoded = ContentCodec.decode(ContentCodec.encode(original))
        assert(decoded is OutboundContent.FileRepairRequest)
        val d = decoded as OutboundContent.FileRepairRequest
        assertEquals(original.fileId, d.fileId)
        assertEquals(original.missingIndices, d.missingIndices)
    }

    @Test
    fun encode_decode_CallSignal_roundtrip() {
        val original = OutboundContent.CallSignal(
            callId = "call-1",
            phase = "offer",
            sdpOrIce = "v=0\r\n...",
        )
        val decoded = ContentCodec.decode(ContentCodec.encode(original))
        assert(decoded is OutboundContent.CallSignal)
        val d = decoded as OutboundContent.CallSignal
        assertEquals(original.callId, d.callId)
        assertEquals(original.phase, d.phase)
        assertEquals(original.sdpOrIce, d.sdpOrIce)
    }

    @Test
    fun encode_decode_HistoryRequest_roundtrip() {
        val original = OutboundContent.HistoryRequest(requestId = "req-123")
        val decoded = ContentCodec.decode(ContentCodec.encode(original))
        assert(decoded is OutboundContent.HistoryRequest)
        assertEquals(original.requestId, (decoded as OutboundContent.HistoryRequest).requestId)
    }

    @Test
    fun encode_decode_HistoryResponse_roundtrip() {
        val original = OutboundContent.HistoryResponse(
            requestId = "req-1",
            messages = listOf(
                HistoryResponseItem("m1", "Hi", 1000L, false, "TEXT"),
                HistoryResponseItem("m2", "Bye", 2000L, true, "TEXT", fileName = "f.pdf"),
            ),
        )
        val decoded = ContentCodec.decode(ContentCodec.encode(original))
        assert(decoded is OutboundContent.HistoryResponse)
        val d = decoded as OutboundContent.HistoryResponse
        assertEquals(original.requestId, d.requestId)
        assertEquals(2, d.messages.size)
        assertEquals("m1", d.messages[0].id)
        assertEquals("Hi", d.messages[0].text)
        assertEquals("f.pdf", d.messages[1].fileName)
    }

    @Test
    fun encode_decode_VoiceNoteMeta_roundtrip() {
        val original = OutboundContent.VoiceNoteMeta(
            fileId = "v1",
            durationMs = 5000L,
            codec = "opus",
        )
        val decoded = ContentCodec.decode(ContentCodec.encode(original))
        assert(decoded is OutboundContent.VoiceNoteMeta)
        val d = decoded as OutboundContent.VoiceNoteMeta
        assertEquals(original.fileId, d.fileId)
        assertEquals(original.durationMs, d.durationMs)
        assertEquals(original.codec, d.codec)
    }

    @Test
    fun encode_decode_VideoNoteMeta_roundtrip() {
        val original = OutboundContent.VideoNoteMeta(
            fileId = "vid1",
            durationMs = 3000L,
            width = 720,
            height = 1280,
            codec = "h264",
        )
        val decoded = ContentCodec.decode(ContentCodec.encode(original))
        assert(decoded is OutboundContent.VideoNoteMeta)
        val d = decoded as OutboundContent.VideoNoteMeta
        assertEquals(original.fileId, d.fileId)
        assertEquals(original.width, d.width)
        assertEquals(original.height, d.height)
    }

    @Test
    fun encode_decode_AudioPacket_roundtrip() {
        val original = OutboundContent.AudioPacket(
            callId = "c1",
            sequenceNumber = 42L,
            timestampMs = 1000L,
            audioDataBase64 = "base64data",
            codec = "pcm_16bit",
            sampleRate = 16000,
        )
        val decoded = ContentCodec.decode(ContentCodec.encode(original))
        assert(decoded is OutboundContent.AudioPacket)
        val d = decoded as OutboundContent.AudioPacket
        assertEquals(original.callId, d.callId)
        assertEquals(original.sequenceNumber, d.sequenceNumber)
        assertEquals(original.sampleRate, d.sampleRate)
    }

    @Test
    fun decode_returns_null_for_invalid_json() {
        assertNull(ContentCodec.decode("not json"))
    }

    @Test
    fun decode_returns_null_for_empty_string() {
        assertNull(ContentCodec.decode(""))
    }

    @Test
    fun ContentKind_has_expected_values() {
        assertEquals(ContentKind.TEXT, ContentKind.valueOf("TEXT"))
        assertEquals(ContentKind.CALL_SIGNAL, ContentKind.valueOf("CALL_SIGNAL"))
    }
}
