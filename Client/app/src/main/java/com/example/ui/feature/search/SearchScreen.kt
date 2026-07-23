package com.example.ui.feature.search

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
import com.example.ui.components.EmptyStateView
import com.example.ui.components.TrackListItem


@Composable
fun SearchTab(
    state: KipotifyUiState,
    onEvent: (KipotifyEvent) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(bottom = 80.dp)) {
        // Search Input field
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = { onEvent(KipotifyEvent.OnSearchQueryChanged(it)) },
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search Icon") },
            trailingIcon = {
                if (state.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onEvent(KipotifyEvent.OnSearchQueryChanged("")) }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .testTag("search_field"),
            shape = RoundedCornerShape(28.dp),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface
            )
        )

        // Filter chips row
        val filters = listOf("All", "Songs", "Artists", "Playlists")
        val filterResIds = listOf(R.string.filter_all, R.string.filter_songs, R.string.filter_artists, R.string.filter_playlists)
        
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            items(filters.size) { index ->
                val filter = filters[index]
                val selected = state.activeSearchFilter == filter
                FilterChip(
                    selected = selected,
                    onClick = { onEvent(KipotifyEvent.OnSelectSearchFilter(filter)) },
                    label = { Text(stringResource(filterResIds[index])) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = Color.White
                    )
                )
            }
        }

        // Search Content router
        if (state.searchQuery.isEmpty()) {
            // Show recent search history (Persisted via Room)
            if (state.searchHistory.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.search_history),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    TextButton(onClick = { onEvent(KipotifyEvent.OnClearSearchHistory) }) {
                        Text(stringResource(R.string.clear_history), color = MaterialTheme.colorScheme.primary)
                    }
                }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.searchHistory, key = { it }) { query ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.large)
                                .clickable { onEvent(KipotifyEvent.OnSearchQueryChanged(query)) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(query, style = MaterialTheme.typography.bodyLarge)
                            }
                            IconButton(onClick = { onEvent(KipotifyEvent.OnDeleteSearchHistory(query)) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                EmptyStateView(
                    icon = Icons.Default.Search,
                    message = stringResource(R.string.search_hint)
                )
            }
        } else {
            // Filter list items based on active filter
            val filteredTracks = state.tracks.filter {
                val matchesQuery = it.title.lowercase().contains(state.searchQuery.lowercase()) ||
                        it.artistName.lowercase().contains(state.searchQuery.lowercase())
                
                when (state.activeSearchFilter) {
                    "Songs" -> matchesQuery
                    "Artists" -> matchesQuery && it.artistName.lowercase().contains(state.searchQuery.lowercase())
                    else -> matchesQuery
                }
            }

            if (filteredTracks.isEmpty()) {
                EmptyStateView(
                    icon = Icons.Default.Refresh,
                    message = stringResource(R.string.no_search_results, state.searchQuery)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    items(filteredTracks, key = { it.id }) { track ->
                        TrackListItem(
                            track = track,
                            onPlay = { onEvent(KipotifyEvent.OnPlayTrack(track, filteredTracks)) },
                            onLike = { onEvent(KipotifyEvent.OnToggleLike(track.id)) }
                        )
                    }
                }
            }
        }
    }
}
