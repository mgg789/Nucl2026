package com.peerdone.app.core.logging

/**
 * Одна запись в журнале логов.
 */
data class LogEntry(
    val level: LogLevel,
    val tag: String,
    val message: String,
    val timestamp: Long,
    val throwable: Throwable? = null
) {
    fun toDisplayLine(): String = "${level.letter} $tag: $message"
}

enum class LogLevel(val letter: Char, val priority: Int) {
    VERBOSE('V', android.util.Log.VERBOSE),
    DEBUG('D', android.util.Log.DEBUG),
    INFO('I', android.util.Log.INFO),
    WARN('W', android.util.Log.WARN),
    ERROR('E', android.util.Log.ERROR);
}
