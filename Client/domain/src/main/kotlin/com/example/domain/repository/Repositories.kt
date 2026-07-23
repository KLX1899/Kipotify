package com.example.domain.repository

import com.example.domain.model.BackendConnectionNotice
import com.example.domain.model.Friend
import com.example.domain.model.Message
import com.example.domain.model.Track
import com.example.domain.model.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface TrackRepository {
    fun observeTracks(): Flow<List<Track>>
    suspend fun refreshTracks(search: String? = null, genre: String? = null): Result<List<Track>>
    suspend fun toggleLike(trackId: String): Result<Boolean>
    suspend fun recordPlay(trackId: String): Result<Unit>
    suspend fun downloadTrack(trackId: String, onProgress: (Int) -> Unit = {}): Boolean
    suspend fun removeDownload(trackId: String)
}

interface SocialRepository {
    val friends: StateFlow<List<Friend>>
    val typingFriendId: StateFlow<String?>
    fun observeMessages(): Flow<List<Message>>
    suspend fun refreshFriends(): Result<List<Friend>>
    suspend fun openChat(friend: Friend?)
    suspend fun toggleFollowFriend(friendId: String): Result<Boolean>
    suspend fun sendMessage(content: String, sharedTrack: Track? = null): Result<Message>
}

interface AccountRepository {
    val theme: StateFlow<String>
    val language: StateFlow<String>
    val isPremium: StateFlow<Boolean>
    suspend fun refreshProfile(): Result<UserProfile>
    suspend fun upgradePremium(): Result<Boolean>
    suspend fun updateSettings(language: String, theme: String): Result<Unit>
}

interface SearchHistoryRepository {
    fun observeRecentSearches(): Flow<List<String>>
    suspend fun save(query: String): Result<Unit>
    suspend fun delete(query: String): Result<Unit>
    suspend fun clear(): Result<Unit>
}

interface ConnectionRepository {
    val notices: StateFlow<BackendConnectionNotice>
}

interface PlaybackController {
    val currentTrack: StateFlow<Track?>
    val isPlaying: StateFlow<Boolean>
    val playbackPosition: StateFlow<Long>
    val durationMs: StateFlow<Long>
    val sleepTimerRemaining: StateFlow<Long>
    val visualizerWaves: StateFlow<List<Float>>
    val dominantColor: StateFlow<Long>

    fun setPlaylist(tracks: List<Track>, playIndex: Int = 0)
    fun pause()
    fun resume()
    fun next()
    fun previous()
    fun setSleepTimer(minutes: Int)
    fun seekTo(positionMs: Long)
}
