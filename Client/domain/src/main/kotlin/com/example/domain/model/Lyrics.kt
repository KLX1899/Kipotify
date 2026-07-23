package com.example.domain.model

data class LyricLine(
    val timestampMs: Long,
    val text: String,
)

sealed interface LyricsLoadResult {
    data class Success(val lines: List<LyricLine>) : LyricsLoadResult
    data object Empty : LyricsLoadResult
    data object Unavailable : LyricsLoadResult
    data class Error(val message: String? = null) : LyricsLoadResult
}
