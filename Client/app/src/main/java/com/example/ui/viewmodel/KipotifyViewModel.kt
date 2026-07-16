package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.KipotifyApplication
import com.example.data.local.entities.SearchHistoryEntity
import com.example.data.model.Friend
import com.example.data.model.Message
import com.example.data.model.Track
import com.example.data.repository.SocialRepository
import com.example.data.repository.TrackRepository
import com.example.playback.AudioPlayerManager
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

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
    
    // Player values
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val playbackPosition: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val sleepTimerRemaining: Long = 0L,
    val visualizerWaves: List<Float> = List(16) { 0.1f },
    val playerDominantColor: Long = 0xFF1E293B
)

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
    data class OnSetPlaybackSpeed(val speed: Float) : KipotifyEvent
    data class OnToggleFollow(val friendId: String) : KipotifyEvent
    data class OnSendMessage(val content: String, val sharedTrack: Track? = null) : KipotifyEvent
    data class OnOpenChatWithFriend(val friend: Friend?) : KipotifyEvent
    data class OnSeekTo(val positionMs: Long) : KipotifyEvent
}

@OptIn(FlowPreview::class)
class KipotifyViewModel(
    application: Application,
    private val trackRepository: TrackRepository,
    private val socialRepository: SocialRepository,
    private val audioPlayerManager: AudioPlayerManager,
    private val settingsManager: com.example.data.local.SettingsManager,
    private val database: com.example.data.local.KipotifyDatabase
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(KipotifyUiState())
    val uiState: StateFlow<KipotifyUiState> = _uiState.asStateFlow()

    private val _searchDebouncedFlow = MutableStateFlow("")
    private var searchJob: Job? = null

    init {
        // Observe tracks
        viewModelScope.launch {
            trackRepository.getTracksFlow().collect { list ->
                _uiState.update { it.copy(tracks = list) }
            }
        }

        // Observe search history from Room
        viewModelScope.launch {
            database.searchHistoryDao().getRecentSearchHistory().collect { entities ->
                _uiState.update { it.copy(searchHistory = entities.map { e -> e.query }) }
            }
        }

        // Observe settings / datastore preferences
        viewModelScope.launch {
            settingsManager.theme.collect { themeVal ->
                _uiState.update { it.copy(theme = themeVal) }
            }
        }
        viewModelScope.launch {
            settingsManager.language.collect { langVal ->
                _uiState.update { it.copy(language = langVal) }
            }
        }
        viewModelScope.launch {
            settingsManager.isPremium.collect { premVal ->
                _uiState.update { it.copy(isPremium = premVal) }
            }
        }

        // Observe social friends
        viewModelScope.launch {
            socialRepository.friends.collect { list ->
                _uiState.update { it.copy(friends = list) }
            }
        }

        // Observe typing indicator from WebSocket simulation
        viewModelScope.launch {
            socialRepository.typingFriendId.collect { friendId ->
                _uiState.update { it.copy(typingFriendId = friendId) }
            }
        }

        // Observe chat messages
        viewModelScope.launch {
            socialRepository.getMessagesFlow().collect { list ->
                _uiState.update { it.copy(chatMessages = list) }
            }
        }

        // Observe media player state flows
        viewModelScope.launch {
            audioPlayerManager.currentTrack.collect { track ->
                _uiState.update { it.copy(currentTrack = track) }
            }
        }
        viewModelScope.launch {
            audioPlayerManager.isPlaying.collect { playState ->
                _uiState.update { it.copy(isPlaying = playState) }
            }
        }
        viewModelScope.launch {
            audioPlayerManager.playbackPosition.collect { pos ->
                _uiState.update { it.copy(playbackPosition = pos) }
            }
        }
        viewModelScope.launch {
            audioPlayerManager.playbackSpeed.collect { speed ->
                _uiState.update { it.copy(playbackSpeed = speed) }
            }
        }
        viewModelScope.launch {
            audioPlayerManager.sleepTimerRemaining.collect { time ->
                _uiState.update { it.copy(sleepTimerRemaining = time) }
            }
        }
        viewModelScope.launch {
            audioPlayerManager.visualizerWaves.collect { waves ->
                _uiState.update { it.copy(visualizerWaves = waves) }
            }
        }
        viewModelScope.launch {
            audioPlayerManager.dominantColor.collect { col ->
                _uiState.update { it.copy(playerDominantColor = col) }
            }
        }

        // Robust debounced live search logic
        viewModelScope.launch {
            _searchDebouncedFlow
                .debounce(500)
                .filter { it.isNotBlank() }
                .collect { query ->
                    database.searchHistoryDao().insertSearch(SearchHistoryEntity(query = query))
                }
        }
    }

    fun onEvent(event: KipotifyEvent) {
        when (event) {
            is KipotifyEvent.OnTabChanged -> {
                _uiState.update { it.copy(activeTab = event.tab) }
            }
            is KipotifyEvent.OnPlayTrack -> {
                audioPlayerManager.setPlaylist(event.list, event.list.indexOfFirst { it.id == event.track.id })
            }
            KipotifyEvent.OnTogglePlay -> {
                if (_uiState.value.isPlaying) {
                    audioPlayerManager.pause()
                } else {
                    audioPlayerManager.resume()
                }
            }
            KipotifyEvent.OnNextTrack -> audioPlayerManager.next()
            KipotifyEvent.OnPrevTrack -> audioPlayerManager.prev()
            is KipotifyEvent.OnToggleLike -> {
                viewModelScope.launch {
                    trackRepository.toggleLike(event.trackId)
                }
            }
            is KipotifyEvent.OnDownloadTrack -> {
                // Downloads restricted to premium users
                if (!_uiState.value.isPremium) return
                
                val progressMap = _uiState.value.downloadingProgress.toMutableMap()
                progressMap[event.trackId] = 0
                _uiState.value = _uiState.value.copy(downloadingProgress = progressMap)

                viewModelScope.launch {
                    trackRepository.downloadTrack(event.trackId) { progress ->
                        val currentMap = _uiState.value.downloadingProgress.toMutableMap()
                        if (progress >= 100) {
                            currentMap.remove(event.trackId)
                        } else {
                            currentMap[event.trackId] = progress
                        }
                        _uiState.update { it.copy(downloadingProgress = currentMap) }
                    }
                }
            }
            is KipotifyEvent.OnRemoveDownload -> {
                viewModelScope.launch {
                    trackRepository.removeDownload(event.trackId)
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
                    database.searchHistoryDao().deleteSearch(event.query)
                }
            }
            KipotifyEvent.OnClearSearchHistory -> {
                viewModelScope.launch {
                    database.searchHistoryDao().clearHistory()
                }
            }
            KipotifyEvent.OnUpgradePremium -> {
                settingsManager.setPremium(true)
            }
            is KipotifyEvent.OnSetLanguage -> {
                settingsManager.setLanguage(event.lang)
            }
            is KipotifyEvent.OnSetTheme -> {
                settingsManager.setTheme(event.theme)
            }
            is KipotifyEvent.OnSetSleepTimer -> {
                audioPlayerManager.setSleepTimer(event.minutes)
            }
            is KipotifyEvent.OnSetPlaybackSpeed -> {
                audioPlayerManager.setSpeed(event.speed)
            }
            is KipotifyEvent.OnToggleFollow -> {
                viewModelScope.launch {
                    socialRepository.toggleFollowFriend(event.friendId)
                }
            }
            is KipotifyEvent.OnSendMessage -> {
                viewModelScope.launch {
                    socialRepository.sendMessage(event.content, event.sharedTrack)
                }
            }
            is KipotifyEvent.OnOpenChatWithFriend -> {
                _uiState.update { it.copy(activeChatFriend = event.friend) }
            }
            is KipotifyEvent.OnSeekTo -> {
                audioPlayerManager.seekTo(event.positionMs)
            }
        }
    }

    class Factory(private val application: KipotifyApplication) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(KipotifyViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return KipotifyViewModel(
                    application = application,
                    trackRepository = application.trackRepository,
                    socialRepository = application.socialRepository,
                    audioPlayerManager = application.audioPlayerManager,
                    settingsManager = application.settingsManager,
                    database = application.database
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
