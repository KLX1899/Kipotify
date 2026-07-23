package com.example.playback

import com.example.ui.formatTime
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackDurationTest {
    @Test
    fun `loaded finite duration is preserved`() {
        assertEquals(191_000L, sanitizeDurationMs(191_000L))
        assertEquals(245_678L, sanitizeDurationMs(245_678L))
    }

    @Test
    fun `different loaded durations are formatted as minutes and seconds`() {
        assertEquals("3:11", formatTime(sanitizeDurationMs(191_033L)))
        assertEquals("4:05", formatTime(sanitizeDurationMs(245_678L)))
    }

    @Test
    fun `unknown and invalid durations reset to zero`() {
        assertEquals(0L, sanitizeDurationMs(0L))
        assertEquals(0L, sanitizeDurationMs(-1L))
        assertEquals(0L, sanitizeDurationMs(Long.MIN_VALUE))
        assertEquals(0L, sanitizeDurationMs(Long.MAX_VALUE))
    }
}
