package com.example.data.repository

import android.content.Context
import com.example.data.local.daos.DownloadedSongDao
import com.example.data.local.daos.LikedSongDao
import com.example.data.local.entities.DownloadedSongEntity
import com.example.data.local.entities.LikedSongEntity
import com.example.data.model.Track
import com.example.data.remote.KipotifyApiClient
import com.example.data.remote.KipotifyApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class TrackRepository(
    private val context: Context,
    private val api: KipotifyApiService,
    private val likedSongDao: LikedSongDao,
    private val downloadedSongDao: DownloadedSongDao
) {
    private val remoteTracks = MutableStateFlow<List<Track>>(emptyList())

    fun getTracksFlow(): Flow<List<Track>> {
        val likedFlow = likedSongDao.getAllLikedSongs()
        val downloadedFlow = downloadedSongDao.getAllDownloadedSongs()

        return combine(remoteTracks, likedFlow, downloadedFlow) { tracks, likedList, downloadedList ->
            val likedIds = likedList.map { it.id }.toSet()
            val downloadedMap = downloadedList.associate { it.id to it.localFilePath }

            tracks.map { track ->
                track.copy(
                    isLiked = track.isLiked || likedIds.contains(track.id),
                    isDownloaded = track.isDownloaded || downloadedMap.containsKey(track.id),
                    localFilePath = downloadedMap[track.id] ?: track.localFilePath
                )
            }
        }
    }

    suspend fun refreshTracks(search: String? = null, genre: String? = null): Result<List<Track>> =
        withContext(Dispatchers.IO) {
            runCatching {
                api.getTracks(genre = genre, search = search)
                    .map { it.toTrack() }
                    .map(::normalizeTrackUrls)
                    .also { remoteTracks.value = it }
            }.onFailure {
                remoteTracks.value = emptyList()
            }
        }

    suspend fun getTrackById(trackId: String): Result<Track> = withContext(Dispatchers.IO) {
        runCatching {
            api.getTrackById(trackId).toTrack().let(::normalizeTrackUrls)
        }
    }

    suspend fun toggleLike(trackId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val response = api.toggleLikeTrack(trackId)
            val track = remoteTracks.value.firstOrNull { it.id == trackId } ?: api.getTrackById(trackId).toTrack().let(::normalizeTrackUrls)
            if (response.isLiked) {
                likedSongDao.insertLikedSong(
                    LikedSongEntity(
                        id = track.id,
                        title = track.title,
                        artistName = track.artistName,
                        coverImageUrl = track.coverImageUrl,
                        audioUrl = track.audioUrl
                    )
                )
            } else {
                likedSongDao.deleteLikedSong(
                    LikedSongEntity(
                        id = track.id,
                        title = track.title,
                        artistName = track.artistName,
                        coverImageUrl = track.coverImageUrl,
                        audioUrl = track.audioUrl
                    )
                )
            }
            remoteTracks.value = remoteTracks.value.map {
                if (it.id == trackId) it.copy(isLiked = response.isLiked) else it
            }
            response.isLiked
        }
    }

    suspend fun recordPlay(trackId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            api.recordTrackPlay(trackId)
            remoteTracks.value = remoteTracks.value.map {
                if (it.id == trackId) it.copy(playCount = it.playCount + 1) else it
            }
        }
    }

    suspend fun canDownload(trackId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching { api.getDownloadEligibility(trackId).canDownload }
    }

    suspend fun downloadTrack(trackId: String, onProgress: (Int) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        val track = remoteTracks.value.firstOrNull { it.id == trackId } ?: getTrackById(trackId).getOrNull() ?: return@withContext false
        val eligible = canDownload(trackId).getOrDefault(false)
        if (!eligible) return@withContext false

        val localDir = File(context.filesDir, "offline_tracks")
        if (!localDir.exists()) localDir.mkdirs()
        val localFile = File(localDir, "${track.id}.mp3")

        val success = runCatching {
            downloadAudio(track.audioUrl, localFile, onProgress)
            api.logTrackDownload(trackId)
        }.isSuccess

        if (!success) {
            if (localFile.exists()) localFile.delete()
            return@withContext false
        }

        downloadedSongDao.insertDownloadedSong(
            DownloadedSongEntity(
                id = track.id,
                localFilePath = localFile.absolutePath,
                title = track.title,
                artistName = track.artistName,
                coverImageUrl = track.coverImageUrl
            )
        )
        remoteTracks.value = remoteTracks.value.map {
            if (it.id == trackId) it.copy(isDownloaded = true, localFilePath = localFile.absolutePath) else it
        }
        onProgress(100)
        true
    }

    suspend fun removeDownload(trackId: String) = withContext(Dispatchers.IO) {
        downloadedSongDao.deleteDownloadedSong(trackId)
        val file = File(context.filesDir, "offline_tracks/$trackId.mp3")
        if (file.exists()) file.delete()
        remoteTracks.value = remoteTracks.value.map {
            if (it.id == trackId) it.copy(isDownloaded = false, localFilePath = null) else it
        }
    }

    private fun downloadAudio(audioUrl: String, targetFile: File, onProgress: (Int) -> Unit) {
        val connection = URL(audioUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        connection.connect()
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("Download failed with HTTP ${connection.responseCode}")
        }

        val totalBytes = connection.contentLengthLong
        connection.inputStream.use { input ->
            targetFile.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var downloaded = 0L
                var read = input.read(buffer)
                while (read >= 0) {
                    output.write(buffer, 0, read)
                    downloaded += read
                    if (totalBytes > 0) {
                        onProgress(((downloaded * 100) / totalBytes).toInt().coerceIn(1, 99))
                    }
                    read = input.read(buffer)
                }
            }
        }
        connection.disconnect()
    }

    private fun normalizeTrackUrls(track: Track): Track {
        return track.copy(
            coverImageUrl = absoluteMediaUrl(track.coverImageUrl),
            fallbackArtworkUrl = absoluteMediaUrl(track.fallbackArtworkUrl),
            audioUrl = absoluteMediaUrl(track.audioUrl),
            lyricsUrl = absoluteMediaUrl(track.lyricsUrl)
        )
    }

    private fun absoluteMediaUrl(value: String): String {
        return KipotifyApiClient.absoluteUrl(value)
    }
}
