package com.example.domain.usecase

import com.example.domain.repository.LyricsRepository

class LoadLyrics(private val repository: LyricsRepository) {
    suspend operator fun invoke(trackId: String, lyricsUrl: String) =
        repository.loadLyrics(trackId, lyricsUrl)
}

data class LyricsUseCases(
    val load: LoadLyrics,
) {
    constructor(repository: LyricsRepository) : this(
        load = LoadLyrics(repository),
    )
}
