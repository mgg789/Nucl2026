package com.peerdone.app.core.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogEntryTest {

    @Test
    fun toDisplayLine_format() {
        val entry = LogEntry(
            level = LogLevel.INFO,
            tag = "App",
            message = "Started",
            timestamp = 1000L,
        )
        assertEquals("I App: Started", entry.toDisplayLine())
    }

    @Test
    fun toDisplayLine_verbose_level_shows_V() {
        val entry = LogEntry(LogLevel.VERBOSE, "TAG", "msg", 0L)
        assertEquals("V TAG: msg", entry.toDisplayLine())
    }

    @Test
    fun toDisplayLine_error_level_shows_E() {
        val entry = LogEntry(LogLevel.ERROR, "Err", "Failed", 0L)
        assertEquals("E Err: Failed", entry.toDisplayLine())
    }

    @Test
    fun LogLevel_letter_and_priority() {
        assertEquals('V', LogLevel.VERBOSE.letter)
        assertEquals('D', LogLevel.DEBUG.letter)
        assertEquals('I', LogLevel.INFO.letter)
        assertEquals('W', LogLevel.WARN.letter)
        assertEquals('E', LogLevel.ERROR.letter)
    }

    @Test
    fun LogEntry_throwable_null_by_default() {
        val entry = LogEntry(LogLevel.INFO, "T", "M", 0L)
        assertNull(entry.throwable)
    }

    @Test
    fun LogEntry_holds_throwable_when_provided() {
        val t = RuntimeException("test")
        val entry = LogEntry(LogLevel.ERROR, "T", "M", 0L, throwable = t)
        assertEquals(t, entry.throwable)
    }
}
