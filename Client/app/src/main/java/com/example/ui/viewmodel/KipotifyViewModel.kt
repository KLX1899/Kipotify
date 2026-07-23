package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.model.BackendConnection
import com.example.domain.model.Friend
import com.example.domain.model.LyricLine
import com.example.domain.model.LyricsLoadResult
import com.example.domain.model.Message
import com.example.domain.model.Track
import com.example.domain.repository.ConnectionRepository
import com.example.domain.repository.PlaybackController
import com.example.domain.usecase.AccountUseCases
import com.example.domain.usecase.DownloadTrackResult
import com.example.domain.usecase.LyricsUseCases
import com.example.domain.usecase.SearchHistoryUseCases
import com.example.domain.usecase.SocialUseCases
import com.example.domain.usecase.TrackUseCases
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

data class KipotifyUiState(
    val tracks: List<Track> = emptyList(),
    val searchHistory: List<String> = emptyList(),
    val searchQuery: String = "",
    val activeSearchFilter: String = "All",
    val isPremium: Boolean = false,
    val language: String = "en",
    val theme: String = "system",
    val activeTab: String = "home",
    val friends: List<Friend> = emptyList(),
    val typingFriendId: String? = null,
    val chatMessages: List<Message> = emptyList(),
    val activeChatFriend: Friend? = null,
    val downloadingProgress: Map<String, Int> = emptyMap(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val backendConnection: BackendConnection = BackendConnection.Discovering,
    val backendConnectionNoticeId: Long = 0L,
    
    // Player values
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val playbackPosition: Long = 0L,
    val durationMs: Long = 0L,
    val sleepTimerRemaining: Long = 0L,
    val visualizerWaves: List<Float> = List(16) { 0.1f },
    val playerDominantColor: Long = 0xFF1E293B,
    val lyrics: LyricsUiState = LyricsUiState.Idle,
)

sealed interface LyricsUiState {
    data object Idle : LyricsUiState
    data object Loading : LyricsUiState
    data class Success(val lines: List<LyricLine>) : LyricsUiState
    data object Empty : LyricsUiState
    data object Unavailable : LyricsUiState
    data class Error(val message: String? = null) : LyricsUiState
}

sealed interface KipotifyEvent {
    data class OnTabChanged(val tab: String) : KipotifyEvent
    data class OnPlayTrack(val track: Track, val list: List<Track>) : KipotifyEvent
    object OnTogglePlay : KipotifyEvent
    object OnNextTrack : KipotifyEvent
    object OnPrevTrack : KipotifyEvent
    data class OnToggleLike(val trackId: String) : KipotifyEvent
    data class OnDownloadTrack(val trackId: String) : KipotifyEvent
    data class OnRemoveDownload(val trackId: String) : KipotifyEvent
    data class OnSearchQueryChanged(val query: String) : KipotifyEvent
    data class OnSelectSearchFilter(val filter: String) : KipotifyEvent
    data class OnDeleteSearchHistory(val query: String) : KipotifyEvent
    object OnClearSearchHistory : KipotifyEvent
    object OnUpgradePremium : KipotifyEvent
    data class OnSetLanguage(val lang: String) : KipotifyEvent
    data class OnSetTheme(val theme: String) : KipotifyEvent
    data class OnSetSleepTimer(val minutes: Int) : KipotifyEvent
    data class OnToggleFollow(val friendId: String) : KipotifyEvent
    data class OnSendMessage(val content: String, val sharedTrack: Track? = null) : KipotifyEvent
    data class OnOpenChatWithFriend(val friend: Friend?) : KipotifyEvent
    data class OnSeekTo(val positionMs: Long) : KipotifyEvent
    data object OnRetryLyrics : KipotifyEvent
}

@OptIn(FlowPreview::class)
@HiltViewModel
class KipotifyViewModel @Inject constructor(
    private val tracks: TrackUseCases,
    private val social: SocialUseCases,
    private val account: AccountUseCases,
    private val searchHistory: SearchHistoryUseCases,
    private val lyrics: LyricsUseCases,
    private val playback: PlaybackController,
    private val connection: ConnectionRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(KipotifyUiState())
    val uiState: StateFlow<KipotifyUiState> = _uiState.asStateFlow()

    private val _searchDebouncedFlow = MutableStateFlow("")
    private val lyricsRetry = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init {
        refreshStartupData()

        viewModelScope.launch {
            connection.notices.collect { notice ->
                _uiState.update {
                    it.copy(
                        backendConnection = notice.connection,
                        backendConnectionNoticeId = notice.id,
                    )
                }
            }
        }

        // Observe tracks
        viewModelScope.launch {
            tracks.observe().collect { list ->
                _uiState.update { it.copy(tracks = list) }
            }
        }

        // Observe search history from Room
        viewModelScope.launch {
            searchHistory.observe()
                .catch {
                    showTransientError("Could not load search history.")
                    emit(emptyList())
                }
                .collect { queries ->
                    _uiState.update { it.copy(searchHistory = queries) }
                }
        }

        val (theme, language, premium) = account.observeSettings()
        viewModelScope.launch {
            theme.collect { themeVal ->
                _uiState.update { it.copy(theme = themeVal) }
            }
        }
        viewModelScope.launch {
            language.collect { langVal ->
                _uiState.update { it.copy(language = langVal) }
            }
        }
        viewModelScope.launch {
            premium.collect { premVal ->
                _uiState.update { it.copy(isPremium = premVal) }
            }
        }

        // Observe social friends
        viewModelScope.launch {
            social.observeFriends().collect { list ->
                _uiState.update { it.copy(friends = list) }
            }
        }

        // Observe typing indicator from WebSocket simulation
        viewModelScope.launch {
            social.observeTypingFriend().collect { friendId ->
                _uiState.update { it.copy(typingFriendId = friendId) }
            }
        }

        // Observe chat messages
        viewModelScope.launch {
            social.observeMessages().collect { list ->
                _uiState.update { it.copy(chatMessages = list) }
            }
        }

        // Observe media player state flows
        viewModelScope.launch {
            combine(
                playback.currentTrack.distinctUntilChangedBy { it?.id to it?.lyricsUrl },
                lyricsRetry.onStart { emit(Unit) },
            ) { track, _ -> track }
                .collectLatest(::loadLyrics)
        }
        viewModelScope.launch {
            playback.isPlaying.collect { playState ->
                _uiState.update { it.copy(isPlaying = playState) }
            }
        }
        viewModelScope.launch {
            playback.playbackPosition.collect { pos ->
                _uiState.update { it.copy(playbackPosition = pos) }
            }
        }
        viewModelScope.launch {
            playback.durationMs.collect { duration ->
                _uiState.update { it.copy(durationMs = duration) }
            }
        }
        viewModelScope.launch {
            playback.sleepTimerRemaining.collect { time ->
                _uiState.update { it.copy(sleepTimerRemaining = time) }
            }
        }
        viewModelScope.launch {
            playback.visualizerWaves.collect { waves ->
                _uiState.update { it.copy(visualizerWaves = waves) }
            }
        }
        viewModelScope.launch {
            playback.dominantColor.collect { col ->
                _uiState.update { it.copy(playerDominantColor = col) }
            }
        }

        // Robust debounced live search logic
        viewModelScope.launch {
            _searchDebouncedFlow
                .debounce(500)
                .map(String::trim)
                .distinctUntilChanged()
                .collectLatest { query ->
                    if (query.isNotEmpty()) {
                        searchHistory.save(query)
                            .onFailure { showTransientError("Could not update search history.") }
                    }
                    tracks.refresh(search = query.ifEmpty { null })
                        .onFailure { error ->
                            showTransientError("Could not refresh tracks.")
                        }
                }
        }
    }

    private fun refreshStartupData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            account.refreshProfile()
                .onFailure { showTransientError("Backend is offline or profile could not load.") }
            tracks.refresh()
                .onFailure { showTransientError("Backend is offline or tracks could not load.") }
            social.refreshFriends()
                .onFailure { showTransientError("Friends could not load.") }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private fun showTransientError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    fun onEvent(event: KipotifyEvent) {
        when (event) {
            is KipotifyEvent.OnTabChanged -> {
                _uiState.update { it.copy(activeTab = event.tab) }
            }
            is KipotifyEvent.OnPlayTrack -> {
                playback.setPlaylist(event.list, event.list.indexOfFirst { it.id == event.track.id })
                viewModelScope.launch {
                    tracks.recordPlay(event.track.id)
                }
            }
            KipotifyEvent.OnTogglePlay -> {
                if (_uiState.value.isPlaying) {
                    playback.pause()
                } else {
                    playback.resume()
                }
            }
            KipotifyEvent.OnNextTrack -> playback.next()
            KipotifyEvent.OnPrevTrack -> playback.previous()
            is KipotifyEvent.OnToggleLike -> {
                viewModelScope.launch {
                    tracks.toggleLike(event.trackId)
                        .onFailure { showTransientError("Could not update like.") }
                }
            }
            is KipotifyEvent.OnDownloadTrack -> {
                val progressMap = _uiState.value.downloadingProgress.toMutableMap()
                progressMap[event.trackId] = 0
                _uiState.value = _uiState.value.copy(downloadingProgress = progressMap)

                viewModelScope.launch {
                    val result = tracks.download(event.trackId) { progress ->
                        val currentMap = _uiState.value.downloadingProgress.toMutableMap()
                        if (progress >= 100) {
                            currentMap.remove(event.trackId)
                        } else {
                            currentMap[event.trackId] = progress
                        }
                        _uiState.update { it.copy(downloadingProgress = currentMap) }
                    }
                    if (result !is DownloadTrackResult.Completed) {
                        val currentMap = _uiState.value.downloadingProgress.toMutableMap()
                        currentMap.remove(event.trackId)
                        _uiState.update { it.copy(downloadingProgress = currentMap) }
                        if (result is DownloadTrackResult.Failed) {
                            showTransientError("Could not download this track.")
                        }
                    }
                }
            }
            is KipotifyEvent.OnRemoveDownload -> {
                viewModelScope.launch {
                    tracks.removeDownload(event.trackId)
                }
            }
            is KipotifyEvent.OnSearchQueryChanged -> {
                _uiState.update { it.copy(searchQuery = event.query) }
                _searchDebouncedFlow.value = event.query
            }
            is KipotifyEvent.OnSelectSearchFilter -> {
                _uiState.update { it.copy(activeSearchFilter = event.filter) }
            }
            is KipotifyEvent.OnDeleteSearchHistory -> {
                viewModelScope.launch {
                    searchHistory.delete(event.query)
                        .onFailure { showTransientError("Could not update search history.") }
                }
            }
            KipotifyEvent.OnClearSearchHistory -> {
                viewModelScope.launch {
                    searchHistory.clear()
                        .onFailure { showTransientError("Could not update search history.") }
                }
            }
            KipotifyEvent.OnUpgradePremium -> {
                viewModelScope.launch {
                    account.upgradePremium()
                        .onFailure { showTransientError("Could not upgrade premium.") }
                }
            }
            is KipotifyEvent.OnSetLanguage -> {
                viewModelScope.launch {
                    account.updateSettings(event.lang, _uiState.value.theme)
                        .onFailure { showTransientError("Could not sync settings.") }
                }
            }
            is KipotifyEvent.OnSetTheme -> {
                viewModelScope.launch {
                    account.updateSettings(_uiState.value.language, event.theme)
                        .onFailure { showTransientError("Could not sync settings.") }
                }
            }
            is KipotifyEvent.OnSetSleepTimer -> {
                playback.setSleepTimer(event.minutes)
            }
            is KipotifyEvent.OnToggleFollow -> {
                viewModelScope.launch {
                    social.toggleFollow(event.friendId)
                        .onFailure { showTransientError("Could not update follow.") }
                }
            }
            is KipotifyEvent.OnSendMessage -> {
                viewModelScope.launch {
                    social.sendMessage(event.content, event.sharedTrack)
                        .onFailure { showTransientError("Could not send message.") }
                }
            }
            is KipotifyEvent.OnOpenChatWithFriend -> {
                _uiState.update { it.copy(activeChatFriend = event.friend) }
                viewModelScope.launch {
                    social.openChat(event.friend)
                }
            }
            is KipotifyEvent.OnSeekTo -> {
                playback.seekTo(event.positionMs)
            }
            KipotifyEvent.OnRetryLyrics -> {
                lyricsRetry.tryEmit(Unit)
            }
        }
    }

    private suspend fun loadLyrics(track: Track?) {
        if (track == null) {
            _uiState.update {
                it.copy(currentTrack = null, lyrics = LyricsUiState.Idle)
            }
            return
        }
        if (track.lyricsUrl.isBlank()) {
            _uiState.update {
                it.copy(currentTrack = track, lyrics = LyricsUiState.Unavailable)
            }
            return
        }

        _uiState.update {
            it.copy(currentTrack = track, lyrics = LyricsUiState.Loading)
        }
        val loadedState = when (val result = lyrics.load(track.id, track.lyricsUrl)) {
            is LyricsLoadResult.Success -> LyricsUiState.Success(result.lines)
            LyricsLoadResult.Empty -> LyricsUiState.Empty
            LyricsLoadResult.Unavailable -> LyricsUiState.Unavailable
            is LyricsLoadResult.Error -> LyricsUiState.Error(result.message)
        }
        _uiState.update { current ->
            if (current.currentTrack?.id == track.id &&
                current.currentTrack.lyricsUrl == track.lyricsUrl
            ) {
                current.copy(lyrics = loadedState)
            } else {
                current
            }
        }
    }

}
