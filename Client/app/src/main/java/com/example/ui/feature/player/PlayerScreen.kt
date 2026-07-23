package com.example.ui.feature.player

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.R
import com.example.domain.model.BackendConnection
import com.example.domain.model.Friend
import com.example.domain.model.Message
import com.example.domain.model.Track
import com.example.domain.lyrics.activeLyricIndex
import com.example.domain.model.LyricLine
import com.example.playback.artwork.embeddedArtwork
import com.example.ui.notification.AutoDismissNotificationController
import com.example.ui.notification.NotificationTimerHandle
import com.example.ui.theme.KipotifyTheme
import com.example.ui.viewmodel.KipotifyEvent
import com.example.ui.viewmodel.KipotifyUiState
import com.example.ui.viewmodel.LyricsUiState
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.ui.viewmodel.KipotifyViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import com.example.ui.components.SongArtworkImage
import com.example.ui.components.TrackArtwork
import com.example.ui.components.formatTime
import com.example.ui.components.shadowScale


@Composable
fun MiniPlayer(
    state: KipotifyUiState,
    onEvent: (KipotifyEvent) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier
) {
    val track = state.currentTrack ?: return

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .shadowScale()
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TrackArtwork(
                track = track,
                modifier = Modifier.size(52.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = track.artistName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { onEvent(KipotifyEvent.OnTogglePlay) }) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Toggle Play",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = { onEvent(KipotifyEvent.OnNextTrack) }) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next Track"
                )
            }
        }
    }
}

@Composable
fun NowPlayingScreen(
    state: KipotifyUiState,
    onEvent: (KipotifyEvent) -> Unit,
    onDismiss: () -> Unit
) {
    val track = state.currentTrack ?: return
    var showLyrics by remember(track.id) { mutableStateOf(false) }
    val isCurrentTrackLiked = state.tracks
        .firstOrNull { it.id == track.id }
        ?.isLiked
        ?: track.isLiked
    
    // Smooth infinite rotation for disc CD
    val infiniteTransition = rememberInfiniteTransition()
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "CD Rotation"
    )
    val actualAngle = if (state.isPlaying) rotationAngle else 0f

    // Background Dominant Color radial gradient
    val dominantHex = state.playerDominantColor
    val dominantColor = Color(dominantHex)
    val backgroundBrush = Brush.radialGradient(
        colors = listOf(dominantColor, Color(0xFF0F172A)),
        center = Offset(500f, 500f),
        radius = 1200f
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp)
    ) {
        val discSize = (maxWidth - 48.dp).coerceIn(220.dp, 280.dp)
        val coverSize = discSize * 0.58f

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Close Player", tint = Color.White)
                }
                Text(
                    text = if (showLyrics) stringResource(R.string.lyrics) else stringResource(R.string.now_playing),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                IconButton(onClick = { onEvent(KipotifyEvent.OnSendMessage("آهنگ رو برات به اشتراک گذاشتم!", track)) }) {
                    Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White)
                }
            }

            AnimatedContent(
                targetState = showLyrics,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                transitionSpec = {
                    fadeIn(tween(250)) togetherWith fadeOut(tween(200))
                },
                label = "Now playing lyrics transition",
            ) { lyricsVisible ->
                if (lyricsVisible) {
                    LyricsContent(
                        state = state.lyrics,
                        playbackPositionMs = state.playbackPosition,
                        onRetry = { onEvent(KipotifyEvent.OnRetryLyrics) },
                        onSeekTo = { onEvent(KipotifyEvent.OnSeekTo(it)) },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(discSize)
                                .rotate(actualAngle)
                                .drawBehind {
                                    drawCircle(Color.Black, radius = size.minDimension / 2)
                                    drawCircle(Color.Gray.copy(alpha = 0.3f), radius = size.minDimension / 2 - 10f, style = Stroke(width = 1f))
                                    drawCircle(Color.Gray.copy(alpha = 0.3f), radius = size.minDimension / 2 - 30f, style = Stroke(width = 1f))
                                    drawCircle(Color.Gray.copy(alpha = 0.3f), radius = size.minDimension / 2 - 50f, style = Stroke(width = 1f))
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            SongArtworkImage(
                                track = track,
                                contentDescription = track.title,
                                modifier = Modifier
                                    .size(coverSize)
                                    .clip(CircleShape)
                                    .border(4.dp, Color.Black, CircleShape),
                                contentScale = ContentScale.Crop
                            )
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .background(Color(0xFF0F172A), CircleShape)
                                    .border(1.dp, Color.LightGray, CircleShape)
                            )
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = track.title,
                                color = Color.White,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = track.artistName,
                                color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        AudioVisualizerCanvas(
                            waves = state.visualizerWaves,
                            isPlaying = state.isPlaying,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp)
                        )
                    }
                }
            }

            val maxMs = state.durationMs
            var draggingPosition by remember(track.id) { mutableStateOf<Float?>(null) }
            val currentSliderValue = draggingPosition ?: state.playbackPosition.toFloat()

            Column {
                Slider(
                    value = currentSliderValue.coerceIn(0f, maxMs.toFloat()),
                    onValueChange = { draggingPosition = it },
                    onValueChangeFinished = {
                        draggingPosition?.let {
                            onEvent(KipotifyEvent.OnSeekTo(it.toLong()))
                        }
                        draggingPosition = null
                    },
                    valueRange = 0f..maxMs.toFloat(),
                    enabled = maxMs > 0L,
                    colors = SliderDefaults.colors(
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatTime(currentSliderValue.toLong()), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
                    Text(formatTime(maxMs), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { onEvent(KipotifyEvent.OnToggleLike(track.id)) }) {
                    Icon(
                        imageVector = if (isCurrentTrackLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Like Song",
                        tint = if (isCurrentTrackLiked) MaterialTheme.colorScheme.primary else Color.White
                    )
                }
                IconButton(onClick = { onEvent(KipotifyEvent.OnPrevTrack) }) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous Track", tint = Color.White, modifier = Modifier.size(36.dp))
                }
                IconButton(
                    onClick = { onEvent(KipotifyEvent.OnTogglePlay) },
                    modifier = Modifier
                        .size(64.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                ) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }
                IconButton(onClick = { onEvent(KipotifyEvent.OnNextTrack) }) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(36.dp))
                }
                IconButton(
                    onClick = { showLyrics = !showLyrics },
                    modifier = Modifier.testTag("lyrics_toggle"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Subject,
                        contentDescription = stringResource(
                            if (showLyrics) R.string.hide_lyrics else R.string.show_lyrics,
                        ),
                        tint = if (showLyrics) MaterialTheme.colorScheme.primary else Color.White,
                    )
                }
            }
        }
    }
}

@Composable
internal fun LyricsContent(
    state: LyricsUiState,
    playbackPositionMs: Long,
    onRetry: () -> Unit,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.testTag("lyrics_content"),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            LyricsUiState.Idle,
            LyricsUiState.Loading -> Column(
                modifier = Modifier.testTag("lyrics_loading"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp,
                )
                Text(
                    text = stringResource(R.string.lyrics_loading),
                    color = Color.White.copy(alpha = 0.8f),
                )
            }

            LyricsUiState.Empty,
            LyricsUiState.Unavailable -> LyricsMessage(
                message = stringResource(R.string.lyrics_unavailable),
                modifier = Modifier.testTag("lyrics_unavailable"),
            )

            is LyricsUiState.Error -> Column(
                modifier = Modifier.testTag("lyrics_error"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LyricsMessage(stringResource(R.string.lyrics_load_error))
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.retry))
                }
            }

            is LyricsUiState.Success -> key(state.lines) {
                SyncedLyricsList(
                    lines = state.lines,
                    playbackPositionMs = playbackPositionMs,
                    onSeekTo = onSeekTo,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun LyricsMessage(
    message: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = message,
        modifier = modifier.padding(horizontal = 24.dp),
        color = Color.White.copy(alpha = 0.85f),
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun SyncedLyricsList(
    lines: List<LyricLine>,
    playbackPositionMs: Long,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val currentPosition by rememberUpdatedState(playbackPositionMs)
    val activeIndex by remember(lines) {
        derivedStateOf { activeLyricIndex(lines, currentPosition) }
    }

    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) {
            var activeItem = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == activeIndex }
            if (activeItem == null) {
                listState.scrollToItem(activeIndex)
                withFrameNanos { }
                activeItem = listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.index == activeIndex }
            }

            activeItem?.let { item ->
                val viewportCenter = (
                    listState.layoutInfo.viewportStartOffset +
                        listState.layoutInfo.viewportEndOffset
                    ) / 2
                val itemCenter = item.offset + item.size / 2
                listState.animateScrollBy((itemCenter - viewportCenter).toFloat())
            }
        }
    }

    BoxWithConstraints(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .testTag("lyrics_list"),
            contentPadding = PaddingValues(vertical = maxHeight / 2),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            itemsIndexed(
                items = lines,
                key = { index, line -> "${line.timestampMs}:$index" },
            ) { index, line ->
                val isActive = index == activeIndex
                Text(
                    text = line.text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSeekTo(line.timestampMs) }
                        .testTag(if (isActive) "active_lyric" else "lyric_line_$index")
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .animateContentSize(),
                    color = if (isActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.White.copy(alpha = 0.55f)
                    },
                    style = if (isActive) {
                        MaterialTheme.typography.headlineSmall
                    } else {
                        MaterialTheme.typography.titleLarge
                    },
                    fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.SemiBold,
                    textAlign = TextAlign.Start,
                )
            }
        }
    }
}

@Composable
fun AudioVisualizerCanvas(
    waves: List<Float>,
    isPlaying: Boolean,
    modifier: Modifier
) {
    val barColor = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val spacing = size.width / (waves.size * 2)
        val barWidth = spacing * 1.2f
        val centerY = size.height / 2

        waves.forEachIndexed { i, wave ->
            val heightMultiplier = if (isPlaying) wave else 0.1f
            val barHeight = size.height * 0.8f * heightMultiplier
            val x = i * (barWidth + spacing) + spacing

            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, centerY - barHeight / 2),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
            )
        }
    }
}

// ==========================================
// REAL-TIME SOCIAL CHAT DIALOG MODULE
// ==========================================
