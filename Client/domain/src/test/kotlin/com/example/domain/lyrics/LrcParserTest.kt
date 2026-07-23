package com.example.domain.lyrics

import com.example.domain.model.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Test

class LrcParserTest {
    @Test
    fun `parses fractional and whole-second timestamps`() {
        val lines = LrcParser.parse(
            """
            [00:12.34]centiseconds
            [01:02.345]milliseconds
            [01:03]whole seconds
            """.trimIndent(),
        )

        assertEquals(listOf(12_340L, 62_345L, 63_000L), lines.map { it.timestampMs })
    }

    @Test
    fun `expands multiple timestamps and sorts the result`() {
        val lines = LrcParser.parse(
            """
            [00:20.00]later
            [00:05.00][00:10.00]repeated
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                LyricLine(5_000L, "repeated"),
                LyricLine(10_000L, "repeated"),
                LyricLine(20_000L, "later"),
            ),
            lines,
        )
    }

    @Test
    fun `applies signed offset to all lines`() {
        val positive = LrcParser.parse("[offset:+250]\n[00:01.00]one")
        val negative = LrcParser.parse("[00:01.00]one\n[offset:-400]")

        assertEquals(1_250L, positive.single().timestampMs)
        assertEquals(600L, negative.single().timestampMs)
    }

    @Test
    fun `ignores metadata blank and malformed lines while preserving unicode`() {
        val lines = LrcParser.parse(
            """
            [ar:هنرمند]
            [ti:ترانه]

            [not a timestamp]ignored
            [00:bad]ignored
            [00:02.00]سلام دنیا
            [00:02.50][by:Kipotify]متن فارسی
            [00:03.00]
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                LyricLine(2_000L, "سلام دنیا"),
                LyricLine(2_500L, "متن فارسی"),
            ),
            lines,
        )
    }

    @Test
    fun `selects active line at boundaries and after backward seeking`() {
        val lines = listOf(
            LyricLine(1_000L, "one"),
            LyricLine(2_000L, "two"),
            LyricLine(3_000L, "three"),
        )

        assertEquals(-1, activeLyricIndex(lines, 999L))
        assertEquals(0, activeLyricIndex(lines, 1_000L))
        assertEquals(1, activeLyricIndex(lines, 2_999L))
        assertEquals(2, activeLyricIndex(lines, 3_000L))
        assertEquals(0, activeLyricIndex(lines, 1_500L))
    }
}
