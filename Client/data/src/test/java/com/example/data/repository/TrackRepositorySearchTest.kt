package com.example.data.repository

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.daos.DownloadedSongDao
import com.example.data.local.daos.LikedSongDao
import com.example.data.local.entities.DownloadedSongEntity
import com.example.data.local.entities.LikedSongEntity
import com.example.data.remote.ApiTrackDto
import com.example.data.remote.KipotifyApiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TrackRepositorySearchTest {

    @Test
    fun slowOlderSearchCannotOverwriteNewerResults() = runBlocking {
        val slowStarted = CountDownLatch(1)
        val releaseSlow = CountDownLatch(1)
        val repository = repository { query ->
            if (query == "slow") {
                slowStarted.countDown()
                check(releaseSlow.await(5, TimeUnit.SECONDS))
                listOf(ApiTrackDto(id = "slow-result", title = "Slow"))
            } else {
                listOf(ApiTrackDto(id = "latest-result", title = "Latest"))
            }
        }

        val slowRequest = async(Dispatchers.Default) { repository.refreshTracks(search = "slow") }
        assertTrue(slowStarted.await(5, TimeUnit.SECONDS))
        repository.refreshTracks(search = "latest").getOrThrow()
        releaseSlow.countDown()
        slowRequest.await().getOrThrow()

        val publishedTracks = withTimeout(2_000) { repository.observeTracks().first() }
        assertEquals(listOf("latest-result"), publishedTracks.map { it.id })
    }

    @Test
    fun malformedTrackIdsAreRemovedBeforeRendering() = runBlocking {
        val repository = repository {
            listOf(
                ApiTrackDto(id = "", title = "Missing id"),
                ApiTrackDto(id = "valid", title = "First"),
                ApiTrackDto(id = "valid", title = "Duplicate"),
            )
        }

        val result = repository.refreshTracks(search = "query").getOrThrow()

        assertEquals(listOf("valid"), result.map { it.id })
        assertEquals(listOf("First"), result.map { it.title })
    }

    @Test
    fun failuresAreReportedAndCancellationIsNotSwallowed() = runBlocking {
        val failedRepository = repository { throw IOException("offline") }
        assertTrue(failedRepository.refreshTracks(search = "query").isFailure)

        val malformedRepository = repository { throw SerializationException("malformed response") }
        assertTrue(malformedRepository.refreshTracks(search = "query").isFailure)

        val cancelledRepository = repository { throw CancellationException("superseded") }
        var cancellationWasRethrown = false
        try {
            cancelledRepository.refreshTracks(search = "query")
        } catch (_: CancellationException) {
            cancellationWasRethrown = true
        }
        assertTrue(cancellationWasRethrown)
    }

    private fun repository(
        getTracks: (String?) -> List<ApiTrackDto>,
    ): TrackRepositoryImpl {
        val api = Proxy.newProxyInstance(
            KipotifyApiService::class.java.classLoader,
            arrayOf(KipotifyApiService::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "getTracks" -> getTracks(arguments?.get(1) as String?)
                else -> throw UnsupportedOperationException(method.name)
            }
        } as KipotifyApiService

        return TrackRepositoryImpl(
            context = ApplicationProvider.getApplicationContext<Context>(),
            api = api,
            likedSongDao = FakeLikedSongDao,
            downloadedSongDao = FakeDownloadedSongDao,
        )
    }

    private object FakeLikedSongDao : LikedSongDao {
        override fun getAllLikedSongs() = MutableStateFlow<List<LikedSongEntity>>(emptyList())
        override fun insertLikedSong(song: LikedSongEntity) = Unit
        override fun deleteLikedSong(song: LikedSongEntity) = Unit
        override fun isSongLiked(id: String) = false
    }

    private object FakeDownloadedSongDao : DownloadedSongDao {
        override fun getAllDownloadedSongs() =
            MutableStateFlow<List<DownloadedSongEntity>>(emptyList())

        override fun insertDownloadedSong(song: DownloadedSongEntity) = Unit
        override fun deleteDownloadedSong(id: String) = Unit
        override fun isSongDownloaded(id: String) = false
    }
}
