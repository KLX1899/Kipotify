package com.example.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.data.model.Track
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AudioPlayerManager(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var exoPlayer: ExoPlayer? = null
    private var playlist: List<Track> = emptyList()
    private var currentIndex: Int = -1

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackPosition = MutableStateFlow(0L)
    val playbackPosition: StateFlow<Long> = _playbackPosition.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _sleepTimerRemaining = MutableStateFlow(0L) // in seconds
    val sleepTimerRemaining: StateFlow<Long> = _sleepTimerRemaining.asStateFlow()

    private val _visualizerWaves = MutableStateFlow<List<Float>>(List(16) { 0.1f })
    val visualizerWaves: StateFlow<List<Float>> = _visualizerWaves.asStateFlow()

    private val _dominantColor = MutableStateFlow(0xFF1E293B) // Default dark slate
    val dominantColor: StateFlow<Long> = _dominantColor.asStateFlow()

    private var sleepTimerJob: Job? = null
    private var visualizerJob: Job? = null
    private var positionJob: Job? = null
    private var isCrossFading = false
    private var crossFadeJob: Job? = null

    init {
        try {
            exoPlayer = ExoPlayer.Builder(context).build().apply {
                repeatMode = Player.REPEAT_MODE_ALL
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlayingChanged: Boolean) {
                        _isPlaying.value = isPlayingChanged
                        if (isPlayingChanged) {
                            startVisualizerSimulation()
                            startPositionUpdates()
                        } else {
                            stopVisualizerSimulation()
                            stopPositionUpdates()
                        }
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) {
                            next()
                        }
                    }
                })
            }
        } catch (e: Exception) {
            // Graceful fallback for headless or restricted Android runtimes
            _isPlaying.value = false
        }
    }

    fun setPlaylist(tracks: List<Track>, playIndex: Int = 0) {
        playlist = tracks
        if (tracks.isNotEmpty() && playIndex in tracks.indices) {
            playTrack(tracks[playIndex])
        }
    }

    fun playTrack(track: Track) {
        val player = exoPlayer
        if (player != null && player.isPlaying && !isCrossFading) {
            crossFadeTo(track)
        } else {
            executeInstantPlay(track)
        }
    }

    private fun executeInstantPlay(track: Track) {
        _currentTrack.value = track
        _dominantColor.value = generateDominantColor(track)
        _playbackPosition.value = 0L
        currentIndex = playlist.indexOfFirst { it.id == track.id }

        val player = exoPlayer
        if (player != null) {
            try {
                player.volume = 1.0f
                player.stop()
                val mediaItem = MediaItem.fromUri(track.playbackUri())
                player.setMediaItem(mediaItem)
                player.prepare()
                player.setPlaybackSpeed(_playbackSpeed.value)
                player.playWhenReady = true
            } catch (e: Exception) {
                // Headless fallback playback simulation
                _isPlaying.value = true
                startVisualizerSimulation()
                startPositionUpdates()
            }
        } else {
            // Emulated fallback playback simulation
            _isPlaying.value = true
            startVisualizerSimulation()
            startPositionUpdates()
        }
    }

    private fun crossFadeTo(track: Track) {
        crossFadeJob?.cancel()
        crossFadeJob = scope.launch {
            isCrossFading = true
            val player = exoPlayer
            if (player != null && player.isPlaying) {
                // Phase 1: Fade out current track
                val steps = 8
                val fadeDuration = 700L
                val delayTime = fadeDuration / steps
                for (i in steps downTo 0) {
                    player.volume = i.toFloat() / steps
                    delay(delayTime)
                }
            }

            // Update current track parameters
            _currentTrack.value = track
            _dominantColor.value = generateDominantColor(track)
            _playbackPosition.value = 0L
            currentIndex = playlist.indexOfFirst { it.id == track.id }

            if (player != null) {
                try {
                    player.stop()
                    player.volume = 0f
                    val mediaItem = MediaItem.fromUri(track.playbackUri())
                    player.setMediaItem(mediaItem)
                    player.prepare()
                    player.setPlaybackSpeed(_playbackSpeed.value)
                    player.playWhenReady = true

                    // Phase 2: Fade in new track
                    val steps = 8
                    val fadeDuration = 700L
                    val delayTime = fadeDuration / steps
                    for (i in 0..steps) {
                        player.volume = i.toFloat() / steps
                        delay(delayTime)
                    }
                } catch (e: Exception) {
                    _isPlaying.value = true
                    startVisualizerSimulation()
                    startPositionUpdates()
                }
            } else {
                _isPlaying.value = true
                startVisualizerSimulation()
                startPositionUpdates()
            }
            isCrossFading = false
        }
    }

    fun pause() {
        val player = exoPlayer
        if (player != null && player.isPlaying) {
            player.pause()
        } else {
            _isPlaying.value = false
            stopVisualizerSimulation()
            stopPositionUpdates()
        }
    }

    fun resume() {
        val player = exoPlayer
        if (player != null) {
            player.play()
        } else if (_currentTrack.value != null) {
            _isPlaying.value = true
            startVisualizerSimulation()
            startPositionUpdates()
        }
    }

    fun next() {
        if (playlist.isEmpty()) return
        val nextIndex = (currentIndex + 1) % playlist.size
        playTrack(playlist[nextIndex])
    }

    fun prev() {
        if (playlist.isEmpty()) return
        var prevIndex = currentIndex - 1
        if (prevIndex < 0) prevIndex = playlist.size - 1
        playTrack(playlist[prevIndex])
    }

    fun setSpeed(speed: Float) {
        _playbackSpeed.value = speed
        exoPlayer?.setPlaybackSpeed(speed)
    }

    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            _sleepTimerRemaining.value = 0L
            return
        }
        
        _sleepTimerRemaining.value = minutes * 60L
        sleepTimerJob = scope.launch {
            while (_sleepTimerRemaining.value > 0) {
                delay(1000)
                _sleepTimerRemaining.value -= 1
            }
            // Sleep timer expired, pause music
            pause()
        }
    }

    private fun Track.playbackUri(): Uri {
        return Uri.parse(audioUrl)
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        _sleepTimerRemaining.value = 0L
    }

    fun seekTo(positionMs: Long) {
        _playbackPosition.value = positionMs
        exoPlayer?.seekTo(positionMs)
    }

    private fun startPositionUpdates() {
        positionJob?.cancel()
        positionJob = scope.launch {
            while (isActive && _isPlaying.value) {
                val player = exoPlayer
                if (player != null) {
                    _playbackPosition.value = player.currentPosition
                    val duration = player.duration
                    if (duration > 0 && duration - player.currentPosition < 1800 && !isCrossFading) {
                        next()
                    }
                } else {
                    val maxDuration = (_currentTrack.value?.durationSeconds ?: 180) * 1000L
                    _playbackPosition.value = (_playbackPosition.value + (1000 * _playbackSpeed.value).toLong()).coerceAtMost(maxDuration)
                    if (_playbackPosition.value >= maxDuration) {
                        next()
                        break
                    }
                }
                delay(1000)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionJob?.cancel()
    }

    private fun startVisualizerSimulation() {
        visualizerJob?.cancel()
        visualizerJob = scope.launch {
            while (isActive && _isPlaying.value) {
                _visualizerWaves.value = List(16) {
                    (0.2f + Math.random().toFloat() * 0.8f)
                }
                delay(100)
            }
        }
    }

    private fun stopVisualizerSimulation() {
        visualizerJob?.cancel()
        _visualizerWaves.value = List(16) { 0.1f }
    }

    private fun generateDominantColor(track: Track): Long {
        // Generate beautiful theme-aligned color palettes based on track properties
        val idSum = track.id.hashCode()
        val palettes = listOf(
            0xFF1E1B4B, // Dark indigo
            0xFF064E3B, // Forest green
            0xFF581C87, // Royal purple
            0xFF7C2D12, // Warm terracotta
            0xFF0F172A, // Deep slate blue
            0xFF1E293B, // Charcoal gray
            0xFF881337  // Velvet red
        )
        val index = Math.abs(idSum) % palettes.size
        return palettes[index]
    }
}
