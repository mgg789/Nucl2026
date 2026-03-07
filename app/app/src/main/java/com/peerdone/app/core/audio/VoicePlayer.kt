package com.peerdone.app.core.audio

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

enum class PlaybackState {
    IDLE,
    PLAYING,
    PAUSED,
    COMPLETED,
    ERROR
}

class VoicePlayer(private val context: Context) {
    
    private var mediaPlayer: MediaPlayer? = null
    private var currentFileId: String? = null
    
    private val _state = MutableStateFlow(PlaybackState.IDLE)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()
    
    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()
    
    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()
    
    private val _playingFileId = MutableStateFlow<String?>(null)
    val playingFileId: StateFlow<String?> = _playingFileId.asStateFlow()
    
    fun play(file: File, fileId: String): Boolean {
        if (!file.exists()) return false
        
        if (currentFileId == fileId && mediaPlayer != null && _state.value == PlaybackState.PAUSED) {
            return resume()
        }
        
        stop()
        
        return try {
            currentFileId = fileId
            _playingFileId.value = fileId
            
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnPreparedListener {
                    _durationMs.value = it.duration.toLong()
                    it.start()
                    _state.value = PlaybackState.PLAYING
                }
                setOnCompletionListener {
                    _state.value = PlaybackState.COMPLETED
                    _currentPositionMs.value = _durationMs.value
                }
                setOnErrorListener { _, _, _ ->
                    _state.value = PlaybackState.ERROR
                    true
                }
                prepareAsync()
            }
            true
        } catch (e: Exception) {
            _state.value = PlaybackState.ERROR
            false
        }
    }
    
    fun playFromBytes(data: ByteArray, fileId: String): Boolean {
        val tempFile = File(context.cacheDir, "voice_play_${fileId}.m4a")
        return try {
            tempFile.writeBytes(data)
            play(tempFile, fileId)
        } catch (e: Exception) {
            _state.value = PlaybackState.ERROR
            false
        }
    }
    
    fun pause(): Boolean {
        return try {
            mediaPlayer?.pause()
            _state.value = PlaybackState.PAUSED
            true
        } catch (e: Exception) {
            false
        }
    }
    
    fun resume(): Boolean {
        return try {
            mediaPlayer?.start()
            _state.value = PlaybackState.PLAYING
            true
        } catch (e: Exception) {
            false
        }
    }
    
    fun stop() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        currentFileId = null
        _playingFileId.value = null
        _state.value = PlaybackState.IDLE
        _currentPositionMs.value = 0
        _durationMs.value = 0
    }
    
    fun seekTo(positionMs: Long) {
        try {
            mediaPlayer?.seekTo(positionMs.toInt())
            _currentPositionMs.value = positionMs
        } catch (_: Exception) {}
    }
    
    fun updatePosition() {
        try {
            mediaPlayer?.currentPosition?.let {
                _currentPositionMs.value = it.toLong()
            }
        } catch (_: Exception) {}
    }
    
    fun release() {
        stop()
    }
}
