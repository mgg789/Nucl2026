package com.peerdone.app.core.logging

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Центральный журнал логов приложения. Хранит последние записи в памяти
 * и отдаёт их в UI. Заполняется через [addEntry] (из LogcatReader) и опционально
 * через [d], [i], [w], [e] из кода.
 */
object AppLogger {

    private const val MAX_ENTRIES = 3000

    private val buffer = CopyOnWriteArrayList<LogEntry>()
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun addEntry(entry: LogEntry) {
        synchronized(buffer) {
            buffer.add(entry)
            while (buffer.size > MAX_ENTRIES) buffer.removeAt(0)
            _entries.value = buffer.toList()
        }
    }

    fun clear() {
        synchronized(buffer) {
            buffer.clear()
            _entries.value = emptyList()
        }
    }

    fun d(tag: String, message: String) {
        val entry = LogEntry(LogLevel.DEBUG, tag, message, System.currentTimeMillis())
        Log.d(tag, message)
        addEntry(entry)
    }

    fun i(tag: String, message: String) {
        val entry = LogEntry(LogLevel.INFO, tag, message, System.currentTimeMillis())
        Log.i(tag, message)
        addEntry(entry)
    }

    fun w(tag: String, message: String) {
        val entry = LogEntry(LogLevel.WARN, tag, message, System.currentTimeMillis())
        Log.w(tag, message)
        addEntry(entry)
    }

    fun e(tag: String, message: String) {
        val entry = LogEntry(LogLevel.ERROR, tag, message, System.currentTimeMillis())
        Log.e(tag, message)
        addEntry(entry)
    }

    fun e(tag: String, message: String, t: Throwable?) {
        val entry = LogEntry(LogLevel.ERROR, tag, "$message ${t?.message ?: ""}", System.currentTimeMillis(), t)
        Log.e(tag, message, t)
        addEntry(entry)
    }
}
