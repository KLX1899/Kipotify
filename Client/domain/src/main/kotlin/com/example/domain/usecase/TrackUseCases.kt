package com.example.domain.usecase

import com.example.domain.repository.AccountRepository
import com.example.domain.repository.TrackRepository

class ObserveTracks(private val repository: TrackRepository) {
    operator fun invoke() = repository.observeTracks()
}

class RefreshTracks(private val repository: TrackRepository) {
    suspend operator fun invoke(search: String? = null, genre: String? = null) =
        repository.refreshTracks(search, genre)
}

class ToggleTrackLike(private val repository: TrackRepository) {
    suspend operator fun invoke(trackId: String) = repository.toggleLike(trackId)
}

class RecordTrackPlay(private val repository: TrackRepository) {
    suspend operator fun invoke(trackId: String) = repository.recordPlay(trackId)
}

sealed interface DownloadTrackResult {
    data object Completed : DownloadTrackResult
    data object RequiresPremium : DownloadTrackResult
    data object Failed : DownloadTrackResult
}

class DownloadTrack(
    private val repository: TrackRepository,
    private val accountRepository: AccountRepository,
) {
    suspend operator fun invoke(
        trackId: String,
        onProgress: (Int) -> Unit = {},
    ): DownloadTrackResult {
        if (!accountRepository.isPremium.value) return DownloadTrackResult.RequiresPremium
        return if (repository.downloadTrack(trackId, onProgress)) {
            DownloadTrackResult.Completed
        } else {
            DownloadTrackResult.Failed
        }
    }
}

class RemoveDownload(private val repository: TrackRepository) {
    suspend operator fun invoke(trackId: String) = repository.removeDownload(trackId)
}

data class TrackUseCases(
    val observe: ObserveTracks,
    val refresh: RefreshTracks,
    val toggleLike: ToggleTrackLike,
    val recordPlay: RecordTrackPlay,
    val download: DownloadTrack,
    val removeDownload: RemoveDownload,
) {
    constructor(
        repository: TrackRepository,
        accountRepository: AccountRepository,
    ) : this(
        ObserveTracks(repository),
        RefreshTracks(repository),
        ToggleTrackLike(repository),
        RecordTrackPlay(repository),
        DownloadTrack(repository, accountRepository),
        RemoveDownload(repository),
    )
}
