package com.peerdone.app.core.logging

import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.regex.Pattern

/**
 * Читает logcat только для процесса приложения и добавляет записи в [AppLogger].
 * Формат logcat -v threadtime: "MM-DD HH:MM:SS.mmm  pid tid level tag: message"
 */
class LogcatReader(private val scope: CoroutineScope) {

    private var job: Job? = null

    // threadtime: "03-07 12:34:56.789  1234  5678 D Tag     : message" (tag may be padded)
    private val threadTimePattern = Pattern.compile(
        "^\\s*(\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+\\d+\\s+\\d+\\s+([VDIWEF])\\s+([^:]+?)\\s*:\\s*(.*)$"
    )

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            try {
                val pid = Process.myPid()
                val process = ProcessBuilder("logcat", "-v", "threadtime", "--pid", pid.toString())
                    .redirectErrorStream(true)
                    .start()
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String? = null
                while (scope.isActive && reader.readLine().also { line = it } != null) {
                    line?.let { parseAndEmit(it) }
                }
            } catch (e: Exception) {
                AppLogger.e("LogcatReader", "Failed to read logcat", e)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun parseAndEmit(line: String) {
        val m = threadTimePattern.matcher(line)
        if (!m.matches()) {
            // Нестандартная строка (например, многострочное сообщение) — сохраняем как INFO
            if (line.isNotBlank()) {
                AppLogger.addEntry(
                    LogEntry(LogLevel.INFO, "", line.trim(), System.currentTimeMillis())
                )
            }
            return
        }
        val level = when (m.group(3)?.singleOrNull()) {
            'V' -> LogLevel.VERBOSE
            'D' -> LogLevel.DEBUG
            'I' -> LogLevel.INFO
            'W' -> LogLevel.WARN
            'E', 'F' -> LogLevel.ERROR
            else -> LogLevel.INFO
        }
        val tag = m.group(4)?.trim() ?: ""
        val message = m.group(5)?.trim() ?: ""
        val timestamp = System.currentTimeMillis() // logcat time would need parsing
        AppLogger.addEntry(LogEntry(level, tag, message, timestamp))
    }
}
