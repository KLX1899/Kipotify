package com.example.data.repository

import android.content.Context
import com.example.data.local.daos.DownloadedSongDao
import com.example.data.local.daos.LikedSongDao
import com.example.data.local.entities.DownloadedSongEntity
import com.example.data.local.entities.LikedSongEntity
import com.example.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.io.File

class TrackRepository(
    private val context: Context,
    private val likedSongDao: LikedSongDao,
    private val downloadedSongDao: DownloadedSongDao
) {
    // Generate at least 50 realistic tracks with real-world names across various genres (Persian & International)
    private val baseTracks: List<Track> = generate50Tracks()

    fun getTracksFlow(): Flow<List<Track>> {
        val likedFlow = likedSongDao.getAllLikedSongs()
        val downloadedFlow = downloadedSongDao.getAllDownloadedSongs()

        return combine(likedFlow, downloadedFlow) { likedList, downloadedList ->
            val likedIds = likedList.map { it.id }.toSet()
            val downloadedMap = downloadedList.associate { it.id to it.localFilePath }

            baseTracks.map { track ->
                track.copy(
                    isLiked = likedIds.contains(track.id),
                    isDownloaded = downloadedMap.containsKey(track.id),
                    localFilePath = downloadedMap[track.id]
                )
            }
        }
    }

    suspend fun toggleLike(trackId: String) = withContext(Dispatchers.IO) {
        val track = baseTracks.find { it.id == trackId } ?: return@withContext
        val isCurrentlyLiked = likedSongDao.isSongLiked(trackId)
        if (isCurrentlyLiked) {
            likedSongDao.deleteLikedSong(
                LikedSongEntity(
                    id = track.id,
                    title = track.title,
                    artistName = track.artistName,
                    coverImageUrl = track.coverImageUrl,
                    audioUrl = track.audioUrl
                )
            )
        } else {
            likedSongDao.insertLikedSong(
                LikedSongEntity(
                    id = track.id,
                    title = track.title,
                    artistName = track.artistName,
                    coverImageUrl = track.coverImageUrl,
                    audioUrl = track.audioUrl
                )
            )
        }
    }

    suspend fun downloadTrack(trackId: String, onProgress: (Int) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        val track = baseTracks.find { it.id == trackId } ?: return@withContext false
        
        // Simulating robust background network download
        for (progress in 10..100 step 15) {
            kotlinx.coroutines.delay(200)
            onProgress(progress)
        }
        
        // Save track to local mock files cache
        val localDir = File(context.cacheDir, "offline_tracks")
        if (!localDir.exists()) localDir.mkdirs()
        val mockLocalFile = File(localDir, "${track.id}.mp3")
        if (!mockLocalFile.exists()) {
            mockLocalFile.writeText("MOCK_MP3_DATA_${track.title}")
        }

        downloadedSongDao.insertDownloadedSong(
            DownloadedSongEntity(
                id = track.id,
                localFilePath = mockLocalFile.absolutePath,
                title = track.title,
                artistName = track.artistName,
                coverImageUrl = track.coverImageUrl
            )
        )
        onProgress(100)
        return@withContext true
    }

    suspend fun removeDownload(trackId: String) = withContext(Dispatchers.IO) {
        downloadedSongDao.deleteDownloadedSong(trackId)
        val file = File(context.cacheDir, "offline_tracks/$trackId.mp3")
        if (file.exists()) file.delete()
    }

    private fun generate50Tracks(): List<Track> {
        val tracks = mutableListOf<Track>()
        
        // Curated sound helix tracks (extremely stable sample audios)
        val audioUrls = listOf(
            "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3",
            "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3",
            "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-3.mp3",
            "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-4.mp3",
            "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-5.mp3",
            "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-6.mp3",
            "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-7.mp3",
            "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-8.mp3"
        )

        // Music illustration covers from Unsplash
        val coverImages = listOf(
            "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=400",
            "https://images.unsplash.com/photo-1470225620780-dba8ba36b745?w=400",
            "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=400",
            "https://images.unsplash.com/photo-1493225457124-a3eb161ffa5f?w=400",
            "https://images.unsplash.com/photo-1459749411175-04bf5292ceea?w=400",
            "https://images.unsplash.com/photo-1505740420928-5e560c06d30e?w=400",
            "https://images.unsplash.com/photo-1487180142328-0c4e37023af5?w=400",
            "https://images.unsplash.com/photo-1516280440614-37939bbacd6a?w=400"
        )

        val persianArtists = listOf(
            "همایون شجریان", "محمدرضا شجریان", "شادمهر عقیلی", "یاس", "سوگند", 
            "علیرضا قربانی", "سیروان خسروی", "محسن یگانه", "سالار عقیلی", "بابک جهانبخش"
        )
        val persianSongs = listOf(
            "آرایش غلیظ", "مرغ سحر", "تقدیر", "سفارشی", "شکوفه", 
            "روزگار غریب", "دوست دارم", "بهت قول میدم", "وطنم", "من و بارون"
        )

        val intArtists = listOf(
            "Chopin", "Vivaldi", "Daft Punk", "Hans Zimmer", "Ludovico Einaudi",
            "Adele", "Coldplay", "Billie Eilish", "The Weeknd", "Dua Lipa"
        )
        val intSongs = listOf(
            "Nocturne Op. 9", "Spring (Four Seasons)", "Get Lucky", "Time (Inception)", "Nuvole Bianche",
            "Someone Like You", "Yellow", "Bad Guy", "Blinding Lights", "Levitating"
        )

        // Generate 50 unique tracks (25 Persian, 25 International)
        for (i in 1..50) {
            val isPersian = i % 2 == 1
            val title: String
            val artist: String
            if (isPersian) {
                val index = (i / 2) % persianSongs.size
                title = persianSongs[index] + " - ${1 + (i / 20)}"
                artist = persianArtists[index]
            } else {
                val index = (i / 2 - 1) % intSongs.size
                title = intSongs[index] + " - Part ${1 + (i / 20)}"
                artist = intArtists[index]
            }

            tracks.add(
                Track(
                    id = "track_$i",
                    title = title,
                    artistName = artist,
                    coverImageUrl = coverImages[i % coverImages.size],
                    audioUrl = audioUrls[i % audioUrls.size],
                    durationSeconds = 120 + (i * 7) % 180
                )
            )
        }
        return tracks
    }
}
