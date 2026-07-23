package com.example.data.repository

import com.example.data.remote.KipotifyApiClient
import com.example.data.remote.KipotifyApiService
import com.example.domain.lyrics.LrcParser
import com.example.domain.model.LyricLine
import com.example.domain.model.LyricsLoadResult
import com.example.domain.repository.LyricsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.LinkedHashMap

class LyricsRepositoryImpl(
    private val api: KipotifyApiService,
) : LyricsRepository {
    private val cache = Collections.synchronizedMap(
        object : LinkedHashMap<String, List<LyricLine>>(MAX_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, List<LyricLine>>,
            ): Boolean = size > MAX_CACHE_ENTRIES
        },
    )

    override suspend fun loadLyrics(trackId: String, lyricsUrl: String): LyricsLoadResult {
        if (lyricsUrl.isBlank()) return LyricsLoadResult.Unavailable
        val normalizedUrl = KipotifyApiClient.absoluteUrl(lyricsUrl)
        val cacheKey = "$trackId|$normalizedUrl"
        cache[cacheKey]?.let { return LyricsLoadResult.Success(it) }

        return withContext(Dispatchers.IO) {
            try {
                val response = api.getLyrics(normalizedUrl)
                if (response.code() == 404) return@withContext LyricsLoadResult.Unavailable
                if (!response.isSuccessful) {
                    return@withContext LyricsLoadResult.Error("HTTP ${response.code()}")
                }

                val content = response.body()?.string().orEmpty()
                val lines = LrcParser.parse(content)
                if (lines.isEmpty()) {
                    LyricsLoadResult.Empty
                } else {
                    cache[cacheKey] = lines
                    LyricsLoadResult.Success(lines)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                LyricsLoadResult.Error(error.message)
            }
        }
    }

    private companion object {
        const val MAX_CACHE_ENTRIES = 50
    }
}
