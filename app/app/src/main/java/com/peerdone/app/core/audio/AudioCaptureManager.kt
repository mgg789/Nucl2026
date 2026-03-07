package com.peerdone.app.core.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

data class AudioFrame(
    val sequenceNumber: Long,
    val timestampMs: Long,
    val pcmData: ByteArray,
    val base64Data: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioFrame) return false
        return sequenceNumber == other.sequenceNumber
    }
    
    override fun hashCode(): Int = sequenceNumber.hashCode()
}

class AudioCaptureManager(private val context: Context) {
    
    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val FRAME_DURATION_MS = 20
        const val FRAME_SIZE = SAMPLE_RATE * FRAME_DURATION_MS / 1000 * 2
    }
    
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    
    private var sequenceNumber = 0L
    
    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()
    
    private val _audioFrames = MutableSharedFlow<AudioFrame>(extraBufferCapacity = 64)
    val audioFrames: SharedFlow<AudioFrame> = _audioFrames.asSharedFlow()
    
    private val _amplitude = MutableStateFlow(0)
    val amplitude: StateFlow<Int> = _amplitude.asStateFlow()
    
    fun start(): Boolean {
        if (_isCapturing.value) return true
        
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) 
            != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            return false
        }
        
        return try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release()
                audioRecord = null
                return false
            }
            
            sequenceNumber = 0L
            audioRecord?.startRecording()
            _isCapturing.value = true
            
            captureJob = scope.launch {
                val buffer = ByteArray(FRAME_SIZE)
                
                while (isActive && _isCapturing.value) {
                    val bytesRead = audioRecord?.read(buffer, 0, FRAME_SIZE) ?: -1
                    
                    if (bytesRead > 0) {
                        val pcmData = buffer.copyOf(bytesRead)
                        val base64 = Base64.encodeToString(pcmData, Base64.NO_WRAP)
                        
                        val frame = AudioFrame(
                            sequenceNumber = sequenceNumber++,
                            timestampMs = System.currentTimeMillis(),
                            pcmData = pcmData,
                            base64Data = base64
                        )
                        
                        _audioFrames.emit(frame)
                        
                        val maxAmp = calculateAmplitude(pcmData)
                        _amplitude.value = maxAmp
                    }
                }
            }
            
            true
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            stop()
            false
        }
    }
    
    fun stop() {
        _isCapturing.value = false
        
        runBlocking {
            captureJob?.cancelAndJoin()
        }
        captureJob = null
        
        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
        
        try {
            audioRecord?.release()
        } catch (_: Exception) {}
        
        audioRecord = null
        _amplitude.value = 0
    }
    
    fun setMuted(muted: Boolean) {
        if (muted) {
            try {
                audioRecord?.stop()
            } catch (_: Exception) {}
        } else {
            try {
                audioRecord?.startRecording()
            } catch (_: Exception) {}
        }
    }
    
    private fun calculateAmplitude(buffer: ByteArray): Int {
        var max = 0
        for (i in buffer.indices step 2) {
            if (i + 1 < buffer.size) {
                val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
                val abs = kotlin.math.abs(sample)
                if (abs > max) max = abs
            }
        }
        return max
    }
}
