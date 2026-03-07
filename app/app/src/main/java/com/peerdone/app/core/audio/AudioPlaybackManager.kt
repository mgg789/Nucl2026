package com.peerdone.app.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentSkipListMap

class JitterBuffer(
    private val maxBufferMs: Int = 200,
    private val targetDelayMs: Int = 60
) {
    private val buffer = ConcurrentSkipListMap<Long, ByteArray>()
    private var nextExpectedSeq = 0L
    private var playoutStarted = false
    
    private var packetsReceived = 0L
    private var packetsLost = 0L
    private var packetsLate = 0L
    
    fun put(sequenceNumber: Long, pcmData: ByteArray) {
        packetsReceived++
        
        if (playoutStarted && sequenceNumber < nextExpectedSeq) {
            packetsLate++
            return
        }
        
        buffer[sequenceNumber] = pcmData
        
        while (buffer.size > maxBufferMs / AudioCaptureManager.FRAME_DURATION_MS) {
            buffer.pollFirstEntry()
        }
    }
    
    fun get(): ByteArray? {
        if (!playoutStarted) {
            if (buffer.size >= targetDelayMs / AudioCaptureManager.FRAME_DURATION_MS) {
                playoutStarted = true
                nextExpectedSeq = buffer.firstKey()
            } else {
                return null
            }
        }
        
        val entry = buffer.remove(nextExpectedSeq)
        
        if (entry == null) {
            packetsLost++
            nextExpectedSeq++
            return ByteArray(AudioCaptureManager.FRAME_SIZE)
        }
        
        nextExpectedSeq++
        return entry
    }
    
    fun clear() {
        buffer.clear()
        nextExpectedSeq = 0
        playoutStarted = false
    }
    
    fun getStats(): JitterStats {
        return JitterStats(
            packetsReceived = packetsReceived,
            packetsLost = packetsLost,
            packetsLate = packetsLate,
            currentBufferSize = buffer.size,
            lossRate = if (packetsReceived > 0) {
                packetsLost.toFloat() / packetsReceived
            } else 0f
        )
    }
    
    fun resetStats() {
        packetsReceived = 0
        packetsLost = 0
        packetsLate = 0
    }
}

data class JitterStats(
    val packetsReceived: Long,
    val packetsLost: Long,
    val packetsLate: Long,
    val currentBufferSize: Int,
    val lossRate: Float
)

class AudioPlaybackManager(private val context: Context) {
    
    companion object {
        const val SAMPLE_RATE = AudioCaptureManager.SAMPLE_RATE
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }
    
    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    
    private val jitterBuffer = JitterBuffer()
    
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    
    private val _volume = MutableStateFlow(1.0f)
    val volume: StateFlow<Float> = _volume.asStateFlow()
    
    fun start(): Boolean {
        if (_isPlaying.value) return true
        
        val bufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioTrack.ERROR || bufferSize == AudioTrack.ERROR_BAD_VALUE) {
            return false
        }
        
        return try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            
            val audioFormat = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .setEncoding(AUDIO_FORMAT)
                .build()
            
            audioTrack = AudioTrack(
                audioAttributes,
                audioFormat,
                bufferSize * 2,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            
            audioTrack?.play()
            _isPlaying.value = true
            
            playbackJob = scope.launch {
                while (isActive && _isPlaying.value) {
                    val frame = jitterBuffer.get()
                    if (frame != null) {
                        audioTrack?.write(frame, 0, frame.size)
                    } else {
                        delay(AudioCaptureManager.FRAME_DURATION_MS.toLong())
                    }
                }
            }
            
            true
        } catch (e: Exception) {
            stop()
            false
        }
    }
    
    fun stop() {
        _isPlaying.value = false
        
        runBlocking {
            playbackJob?.cancelAndJoin()
        }
        playbackJob = null
        
        try {
            audioTrack?.stop()
        } catch (_: Exception) {}
        
        try {
            audioTrack?.release()
        } catch (_: Exception) {}
        
        audioTrack = null
        jitterBuffer.clear()
    }
    
    fun enqueueFrame(sequenceNumber: Long, pcmData: ByteArray) {
        jitterBuffer.put(sequenceNumber, pcmData)
    }
    
    fun enqueueBase64Frame(sequenceNumber: Long, base64Data: String) {
        try {
            val pcmData = Base64.decode(base64Data, Base64.NO_WRAP)
            enqueueFrame(sequenceNumber, pcmData)
        } catch (_: Exception) {}
    }
    
    fun setVolume(volume: Float) {
        val clampedVolume = volume.coerceIn(0f, 1f)
        _volume.value = clampedVolume
        audioTrack?.setVolume(clampedVolume)
    }
    
    fun setSpeakerphoneOn(enabled: Boolean) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioManager?.isSpeakerphoneOn = enabled
    }
    
    fun getJitterStats(): JitterStats {
        return jitterBuffer.getStats()
    }
    
    fun resetStats() {
        jitterBuffer.resetStats()
    }
}
