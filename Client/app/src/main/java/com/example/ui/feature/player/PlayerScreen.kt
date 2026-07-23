package com.example.ui.feature.player

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import com.example.playback.artwork.embeddedArtwork
import com.example.ui.notification.AutoDismissNotificationController
import com.example.ui.notification.NotificationTimerHandle
import com.example.ui.theme.KipotifyTheme
import com.example.ui.viewmodel.KipotifyEvent
import com.example.ui.viewmodel.KipotifyUiState
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
            verticalArrangement = Arrangement.SpaceBetween
        ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Close Player", tint = Color.White)
            }
            Text(
                text = stringResource(R.string.now_playing),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
            IconButton(onClick = { onEvent(KipotifyEvent.OnSendMessage("آهنگ رو برات به اشتراک گذاشتم!", track)) }) {
                Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White)
            }
        }

        // Animated Rotating Vinyl CD Disc
        Box(
            modifier = Modifier
                .size(discSize)
                .rotate(actualAngle)
                .drawBehind {
                    // Draw outer black grooves
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
            // Center pin hole
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(Color(0xFF0F172A), CircleShape)
                    .border(1.dp, Color.LightGray, CircleShape)
            )
        }

        // Metadata Info Block
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

        // Custom drawn Canvas Audio Wave visualizer
        AudioVisualizerCanvas(
            waves = state.visualizerWaves,
            isPlaying = state.isPlaying,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
        )

        // Progress Seek Bar
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

        // Main Controls Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onEvent(KipotifyEvent.OnToggleLike(track.id)) }) {
                Icon(
                    imageVector = if (isCurrentTrackLiked) {
                        Icons.Default.Favorite
                    } else {
                        Icons.Default.FavoriteBorder
                    },
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
            IconButton(onClick = { onEvent(KipotifyEvent.OnSetSleepTimer(if (state.sleepTimerRemaining == 0L) 15 else 0)) }) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = "Sleep Timer",
                    tint = if (state.sleepTimerRemaining > 0L) MaterialTheme.colorScheme.primary else Color.White
                )
            }
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
