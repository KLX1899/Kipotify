package com.example.ui.app

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
import com.example.ui.feature.category.CategoryDetailView
import com.example.ui.feature.downloads.DownloadsTab
import com.example.ui.feature.home.HomeTab
import com.example.ui.feature.player.MiniPlayer
import com.example.ui.feature.player.NowPlayingScreen
import com.example.ui.feature.playlists.PlaylistsTab
import com.example.ui.feature.profile.ProfileTab
import com.example.ui.feature.search.SearchTab
import com.example.ui.feature.social.SocialChatHub

private const val CONNECTION_NOTIFICATION_DURATION_MILLIS = 4_500L


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(
    state: KipotifyUiState,
    onEvent: (KipotifyEvent) -> Unit
) {
    var isPlayerExpanded by remember { mutableStateOf(false) }
    var showChatDialog by remember { mutableStateOf(false) }
    var showUpgradeDialog by remember { mutableStateOf(false) }
    var activeCategoryDetail by remember { mutableStateOf<String?>(null) }
    var visibleConnectionNotification by remember {
        mutableStateOf<BackendConnection?>(null)
    }
    val notificationScope = rememberCoroutineScope()
    val connectionNotificationController = remember(notificationScope) {
        AutoDismissNotificationController<BackendConnection>(
            durationMillis = CONNECTION_NOTIFICATION_DURATION_MILLIS,
            scheduleDismiss = { delayMillis, onElapsed ->
                val timerJob = notificationScope.launch {
                    delay(delayMillis)
                    onElapsed()
                }
                NotificationTimerHandle { timerJob.cancel() }
            },
            onNotificationChanged = { visibleConnectionNotification = it },
        )
    }

    // Upgrade premium dialog trigger from download block
    LaunchedEffect(state.isPremium) {
        if (state.isPremium) showUpgradeDialog = false
    }

    LaunchedEffect(state.backendConnectionNoticeId) {
        when (val connection = state.backendConnection) {
            is BackendConnection.Connected -> connectionNotificationController.dismiss()
            else -> connectionNotificationController.show(connection)
        }
    }

    DisposableEffect(connectionNotificationController) {
        onDispose {
            connectionNotificationController.dispose()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.img_app_icon),
                            contentDescription = "App Logo",
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(id = R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        if (state.backendConnection !is BackendConnection.Connected) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = when (state.backendConnection) {
                                    BackendConnection.Discovering,
                                    is BackendConnection.Reconnecting -> Icons.Default.Sync
                                    is BackendConnection.Fallback -> Icons.Default.CloudOff
                                    is BackendConnection.Unavailable -> Icons.Default.ErrorOutline
                                    is BackendConnection.Connected -> Icons.Default.Wifi
                                },
                                contentDescription = state.backendConnection.message,
                                tint = when (state.backendConnection) {
                                    is BackendConnection.Unavailable -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showChatDialog = true },
                        modifier = Modifier.testTag("chat_nav_button")
                    ) {
                        BadgedBox(
                            badge = {
                                if (state.typingFriendId != null) {
                                    Badge(containerColor = MaterialTheme.colorScheme.tertiary)
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Notifications,
                                contentDescription = "Social Hub",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(
                        onClick = { onEvent(KipotifyEvent.OnTabChanged("profile")) },
                        modifier = Modifier.testTag("settings_nav_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.96f),
                    scrolledContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                windowInsets = WindowInsets.navigationBars
            ) {
                NavigationBarItem(
                    selected = state.activeTab == "home",
                    onClick = {
                        activeCategoryDetail = null
                        onEvent(KipotifyEvent.OnTabChanged("home"))
                    },
                    icon = { Icon(if (state.activeTab == "home") Icons.Filled.Home else Icons.Outlined.Home, contentDescription = "Home") },
                    label = { Text(stringResource(R.string.tab_home)) },
                    modifier = Modifier.testTag("tab_home")
                )
                NavigationBarItem(
                    selected = state.activeTab == "search",
                    onClick = {
                        activeCategoryDetail = null
                        onEvent(KipotifyEvent.OnTabChanged("search"))
                    },
                    icon = { Icon(if (state.activeTab == "search") Icons.Filled.Search else Icons.Outlined.Search, contentDescription = "Search") },
                    label = { Text(stringResource(R.string.tab_search)) },
                    modifier = Modifier.testTag("tab_search")
                )
                NavigationBarItem(
                    selected = state.activeTab == "downloads",
                    onClick = {
                        activeCategoryDetail = null
                        onEvent(KipotifyEvent.OnTabChanged("downloads"))
                    },
                    icon = { Icon(if (state.activeTab == "downloads") Icons.Default.KeyboardArrowDown else Icons.Outlined.KeyboardArrowDown, contentDescription = "Downloads") },
                    label = { Text(stringResource(R.string.tab_downloads)) },
                    modifier = Modifier.testTag("tab_downloads")
                )
                NavigationBarItem(
                    selected = state.activeTab == "playlists",
                    onClick = {
                        activeCategoryDetail = null
                        onEvent(KipotifyEvent.OnTabChanged("playlists"))
                    },
                    icon = { Icon(if (state.activeTab == "playlists") Icons.Filled.List else Icons.Outlined.List, contentDescription = "Playlists") },
                    label = { Text(stringResource(R.string.tab_playlists)) },
                    modifier = Modifier.testTag("tab_playlists")
                )
                NavigationBarItem(
                    selected = state.activeTab == "profile",
                    onClick = {
                        activeCategoryDetail = null
                        onEvent(KipotifyEvent.OnTabChanged("profile"))
                    },
                    icon = { Icon(if (state.activeTab == "profile") Icons.Filled.Person else Icons.Outlined.Person, contentDescription = "Profile") },
                    label = { Text(stringResource(R.string.tab_profile)) },
                    modifier = Modifier.testTag("tab_profile")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Main Content router
            if (activeCategoryDetail != null) {
                CategoryDetailView(
                    categoryName = activeCategoryDetail!!,
                    state = state,
                    onEvent = onEvent,
                    onBack = { activeCategoryDetail = null }
                )
            } else {
                AnimatedContent(
                    targetState = state.activeTab,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(220)) + slideInHorizontally(
                            animationSpec = tween(300),
                            initialOffsetX = { if (targetState == "profile" || targetState == "playlists") it else -it }
                        ) togetherWith fadeOut(animationSpec = tween(150)) + slideOutHorizontally(
                            animationSpec = tween(220),
                            targetOffsetX = { if (targetState == "profile" || targetState == "playlists") -it else it }
                        )
                    },
                    label = "Tab Transition"
                ) { targetTab ->
                    when (targetTab) {
                        "home" -> HomeTab(
                            state = state,
                            onEvent = onEvent,
                            onCategorySelect = { activeCategoryDetail = it }
                        )
                        "search" -> SearchTab(state = state, onEvent = onEvent)
                        "downloads" -> DownloadsTab(
                            state = state,
                            onEvent = onEvent,
                            onShowUpgradeDialog = { showUpgradeDialog = true }
                        )
                        "playlists" -> PlaylistsTab(
                            state = state,
                            onEvent = onEvent,
                            onCategorySelect = { activeCategoryDetail = it }
                        )
                        "profile" -> ProfileTab(state = state, onEvent = onEvent)
                    }
                }
            }

            visibleConnectionNotification?.let { connectionNotification ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = if (connectionNotification is BackendConnection.Unavailable) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    tonalElevation = 3.dp
                ) {
                    Text(
                        text = connectionNotification.message,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = if (connectionNotification is BackendConnection.Unavailable) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Floating Mini Player (only displayed when a track is active)
            if (state.currentTrack != null && !isPlayerExpanded) {
                MiniPlayer(
                    state = state,
                    onEvent = onEvent,
                    onClick = { isPlayerExpanded = true },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            // Real-time Social Chat Overlay
            if (showChatDialog) {
                SocialChatHub(
                    state = state,
                    onEvent = onEvent,
                    onDismiss = { showChatDialog = false }
                )
            }

            // Premium required Upgrade popup helper
            if (showUpgradeDialog) {
                AlertDialog(
                    onDismissRequest = { showUpgradeDialog = false },
                    title = { Text(stringResource(R.string.premium_required_title)) },
                    text = { Text(stringResource(R.string.premium_required_desc)) },
                    confirmButton = {
                        Button(
                            onClick = {
                                onEvent(KipotifyEvent.OnUpgradePremium)
                                showUpgradeDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                        ) {
                            Text(stringResource(R.string.upgrade_to_premium))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showUpgradeDialog = false }) {
                            Text(stringResource(R.string.dismiss))
                        }
                    }
                )
            }

            // Full Screen Now Playing sliding overlay
            AnimatedVisibility(
                visible = isPlayerExpanded,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                if (state.currentTrack != null) {
                    NowPlayingScreen(
                        state = state,
                        onEvent = onEvent,
                        onDismiss = { isPlayerExpanded = false }
                    )
                }
            }
        }
    }
}

// ==========================================
// TABS & SUB-VIEWS
// ==========================================
