package com.example.domain.lyrics

import com.example.domain.model.LyricLine

object LrcParser {
    private val timestamp = Regex("""\[(\d{1,3}):([0-5]?\d)(?:[.:](\d{1,3}))?]""")
    private val offset = Regex("""(?i)\[offset:\s*([+-]?\d+)\s*]""")
    private val metadata = Regex("""(?i)\[(?:ar|ti|al|by|offset):[^]]*]""")

    fun parse(content: String): List<LyricLine> {
        val offsetMs = offset.findAll(content)
            .lastOrNull()
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
            ?: 0L

        return content.lineSequence()
            .flatMap { sourceLine ->
                val matches = timestamp.findAll(sourceLine).toList()
                if (matches.isEmpty()) return@flatMap emptySequence()

                val text = metadata.replace(timestamp.replace(sourceLine, ""), "").trim()
                if (text.isEmpty()) return@flatMap emptySequence()

                matches.asSequence().mapNotNull { match ->
                    timestampMs(match)?.let { parsedTimestamp ->
                        LyricLine(
                            timestampMs = (parsedTimestamp + offsetMs).coerceAtLeast(0L),
                            text = text,
                        )
                    }
                }
            }
            .sortedBy(LyricLine::timestampMs)
            .toList()
    }

    private fun timestampMs(match: MatchResult): Long? {
        val minutes = match.groupValues[1].toLongOrNull() ?: return null
        val seconds = match.groupValues[2].toLongOrNull() ?: return null
        val fraction = match.groupValues[3]
        val fractionMs = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLongOrNull()?.times(100L)
            2 -> fraction.toLongOrNull()?.times(10L)
            else -> fraction.take(3).toLongOrNull()
        } ?: return null
        return minutes * 60_000L + seconds * 1_000L + fractionMs
    }
}

fun activeLyricIndex(lines: List<LyricLine>, playbackPositionMs: Long): Int {
    var low = 0
    var high = lines.lastIndex
    var result = -1
    while (low <= high) {
        val middle = (low + high).ushr(1)
        if (lines[middle].timestampMs <= playbackPositionMs) {
            result = middle
            low = middle + 1
        } else {
            high = middle - 1
        }
    }
    return result
}
