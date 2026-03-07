package com.peerdone.app.core.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

data class VoiceRecordingResult(
    val fileId: String,
    val file: File,
    val durationMs: Long,
    val codec: String = "aac"
)

enum class RecordingState {
    IDLE,
    RECORDING,
    PAUSED,
    ERROR
}

class VoiceRecorder(private val context: Context) {
    
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startTimeMs: Long = 0
    private var currentFileId: String? = null
    
    private val _state = MutableStateFlow(RecordingState.IDLE)
    val state: StateFlow<RecordingState> = _state.asStateFlow()
    
    private val _amplitude = MutableStateFlow(0)
    val amplitude: StateFlow<Int> = _amplitude.asStateFlow()
    
    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()
    
    fun startRecording(): Boolean {
        if (_state.value == RecordingState.RECORDING) return false
        
        return try {
            currentFileId = UUID.randomUUID().toString()
            outputFile = File(context.cacheDir, "voice_${currentFileId}.m4a")
            
            mediaRecorder = createMediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64000)
                setAudioSamplingRate(16000)
                setAudioChannels(1)
                setOutputFile(outputFile?.absolutePath)
                prepare()
                start()
            }
            
            startTimeMs = System.currentTimeMillis()
            _state.value = RecordingState.RECORDING
            true
        } catch (e: Exception) {
            _state.value = RecordingState.ERROR
            cleanup()
            false
        }
    }
    
    fun stopRecording(): VoiceRecordingResult? {
        if (_state.value != RecordingState.RECORDING) return null
        
        return try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
            
            val duration = System.currentTimeMillis() - startTimeMs
            _durationMs.value = duration
            _state.value = RecordingState.IDLE
            
            outputFile?.let { file ->
                if (file.exists() && duration > 500) {
                    VoiceRecordingResult(
                        fileId = currentFileId ?: UUID.randomUUID().toString(),
                        file = file,
                        durationMs = duration,
                        codec = "aac"
                    )
                } else {
                    file.delete()
                    null
                }
            }
        } catch (e: Exception) {
            cleanup()
            _state.value = RecordingState.IDLE
            null
        }
    }
    
    fun cancelRecording() {
        cleanup()
        outputFile?.delete()
        _state.value = RecordingState.IDLE
    }
    
    fun getMaxAmplitude(): Int {
        return try {
            val amp = mediaRecorder?.maxAmplitude ?: 0
            _amplitude.value = amp
            amp
        } catch (e: Exception) {
            0
        }
    }
    
    fun getCurrentDurationMs(): Long {
        return if (_state.value == RecordingState.RECORDING) {
            System.currentTimeMillis() - startTimeMs
        } else {
            _durationMs.value
        }
    }
    
    private fun cleanup() {
        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {}
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null
    }
    
    @Suppress("DEPRECATION")
    private fun createMediaRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
    }
}
