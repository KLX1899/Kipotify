package com.example

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
import coil.compose.AsyncImage
import com.example.data.local.artwork.embeddedArtwork
import com.example.data.model.Friend
import com.example.data.model.Message
import com.example.data.model.Track
import com.example.data.remote.BackendConnectionState
import com.example.ui.notification.AutoDismissNotificationController
import com.example.ui.notification.NotificationTimerHandle
import com.example.ui.theme.KipotifyTheme
import com.example.ui.viewmodel.KipotifyEvent
import com.example.ui.viewmodel.KipotifyUiState
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.ui.viewmodel.KipotifyViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

private const val CONNECTION_NOTIFICATION_DURATION_MILLIS = 4_500L

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val app = LocalContext.current.applicationContext as KipotifyApplication
            val viewModel: KipotifyViewModel = viewModel(
                factory = KipotifyViewModel.Factory(app)
            )
            val state by viewModel.uiState.collectAsState()

            // Request Notification Permission on Android 13/14+
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                val requestPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { _ -> }
                LaunchedEffect(Unit) {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            // Dynamic Locale Configuration
            val locale = Locale(state.language)
            Locale.setDefault(locale)
            val resources = LocalContext.current.resources
            val config = resources.configuration
            config.setLocale(locale)
            resources.updateConfiguration(config, resources.displayMetrics)

            val systemTheme = isSystemInDarkTheme()
            val useDarkTheme = when (state.theme) {
                "dark" -> true
                "light" -> false
                else -> systemTheme
            }

            var showSplash by remember { mutableStateOf(true) }

            KipotifyTheme(darkTheme = useDarkTheme) {
                val layoutDirection = if (state.language == "fa") LayoutDirection.Rtl else LayoutDirection.Ltr
                CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                    AnimatedContent(
                        targetState = showSplash,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(400)) togetherWith fadeOut(animationSpec = tween(450))
                        },
                        label = "Splash To App Transition"
                    ) { isSplash ->
                        if (isSplash) {
                            SplashScreen(onTimeout = { showSplash = false })
                        } else {
                            AppShell(state = state, onEvent = viewModel::onEvent)
                        }
                    }
                }
            }
        }
    }
}

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
        mutableStateOf<BackendConnectionState?>(null)
    }
    val notificationScope = rememberCoroutineScope()
    val connectionNotificationController = remember(notificationScope) {
        AutoDismissNotificationController<BackendConnectionState>(
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
            is BackendConnectionState.Connected -> connectionNotificationController.dismiss()
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
                        if (state.backendConnection !is BackendConnectionState.Connected) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = when (state.backendConnection) {
                                    BackendConnectionState.Discovering,
                                    is BackendConnectionState.Reconnecting -> Icons.Default.Sync
                                    is BackendConnectionState.Fallback -> Icons.Default.CloudOff
                                    is BackendConnectionState.Unavailable -> Icons.Default.ErrorOutline
                                    is BackendConnectionState.Connected -> Icons.Default.Wifi
                                },
                                contentDescription = state.backendConnection.message,
                                tint = when (state.backendConnection) {
                                    is BackendConnectionState.Unavailable -> MaterialTheme.colorScheme.error
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
                    color = if (connectionNotification is BackendConnectionState.Unavailable) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    tonalElevation = 3.dp
                ) {
                    Text(
                        text = connectionNotification.message,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = if (connectionNotification is BackendConnectionState.Unavailable) {
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

@Composable
fun HomeTab(
    state: KipotifyUiState,
    onEvent: (KipotifyEvent) -> Unit,
    onCategorySelect: (String) -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(bottom = 80.dp) // space for mini player
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        if (state.tracks.isEmpty()) {
            EmptyStateView(
                icon = Icons.Default.MusicNote,
                message = stringResource(R.string.playlist_empty),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 320.dp)
            )
            return@Column
        }

        SectionHeader(title = stringResource(R.string.carousel_title))
        FeaturedCarousel(tracks = state.tracks.take(5), onEvent = onEvent)

        SectionHeader(
            title = stringResource(R.string.quick_actions),
            modifier = Modifier.padding(top = 8.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            QuickActionButton(
                title = stringResource(R.string.action_liked_songs),
                icon = Icons.Default.Favorite,
                backgroundColor = Color(0xFFE11D48),
                modifier = Modifier.weight(1f),
                onClick = { onCategorySelect("Liked") }
            )
            QuickActionButton(
                title = stringResource(R.string.action_recent),
                icon = Icons.Default.Refresh,
                backgroundColor = Color(0xFF6366F1),
                modifier = Modifier.weight(1f),
                onClick = { onCategorySelect("Recent") }
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            QuickActionButton(
                title = stringResource(R.string.action_my_playlists),
                icon = Icons.Default.List,
                backgroundColor = Color(0xFF10B981),
                modifier = Modifier.weight(1f),
                onClick = { onCategorySelect("User") }
            )
            QuickActionButton(
                title = stringResource(R.string.action_top_artists),
                icon = Icons.Default.Person,
                backgroundColor = Color(0xFFD97706),
                modifier = Modifier.weight(1f),
                onClick = { onCategorySelect("Artists") }
            )
        }

        // Section rows
        HomeTrackRow(
            title = stringResource(R.string.section_popular),
            tracks = state.tracks.take(12),
            onEvent = onEvent
        )
        HomeTrackRow(
            title = stringResource(R.string.section_new_releases),
            tracks = state.tracks.drop(12).take(12),
            onEvent = onEvent
        )
    }
}

@Composable
fun QuickActionButton(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
        onClick = onClick,
        modifier = modifier
            .height(68.dp)
            .shadowScale()
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(backgroundColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun FeaturedCarousel(
    tracks: List<Track>,
    onEvent: (KipotifyEvent) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val cardWidth = (maxWidth - 32.dp).coerceAtMost(360.dp)

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(tracks, key = { it.id }) { track ->
                Card(
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .width(cardWidth)
                        .height(172.dp)
                        .shadowScale(),
                    onClick = { onEvent(KipotifyEvent.OnPlayTrack(track, tracks)) }
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        SongArtworkImage(
                            track = track,
                            contentDescription = track.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            Color.Black.copy(alpha = 0.82f)
                                        )
                                    )
                                )
                        )
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(18.dp)
                        ) {
                            Text(
                                text = track.title,
                                color = Color.White,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = track.artistName,
                                color = Color.White.copy(alpha = 0.82f),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HomeTrackRow(
    title: String,
    tracks: List<Track>,
    onEvent: (KipotifyEvent) -> Unit
) {
    Column(modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)) {
        SectionHeader(title = title, compact = true)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(tracks, key = { it.id }) { track ->
                Column(
                    modifier = Modifier
                        .width(132.dp)
                        .clip(MaterialTheme.shapes.large)
                        .clickable { onEvent(KipotifyEvent.OnPlayTrack(track, tracks)) }
                        .padding(6.dp)
                ) {
                    TrackArtwork(
                        track = track,
                        modifier = Modifier.size(120.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track.artistName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

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

@Composable
fun DownloadsTab(
    state: KipotifyUiState,
    onEvent: (KipotifyEvent) -> Unit,
    onShowUpgradeDialog: () -> Unit
) {
    val downloadedTracks = state.tracks.filter { it.isDownloaded }

    Column(modifier = Modifier.fillMaxSize().padding(bottom = 80.dp)) {
        SectionHeader(title = stringResource(R.string.downloads_title))

        if (downloadedTracks.isEmpty()) {
            EmptyStateView(
                icon = Icons.Outlined.KeyboardArrowDown,
                message = stringResource(R.string.downloads_empty),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
            // Add a shortcut upgrade button for non-premium
            if (!state.isPremium) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Button(
                        onClick = onShowUpgradeDialog,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                    ) {
                        Text(stringResource(R.string.upgrade_to_premium))
                    }
                }
            }
        } else {
            // Show downloading queues if active
            if (state.downloadingProgress.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.downloading_work))
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                items(downloadedTracks, key = { it.id }) { track ->
                    TrackListItem(
                        track = track,
                        onPlay = { onEvent(KipotifyEvent.OnPlayTrack(track, downloadedTracks)) },
                        trailingContent = {
                            IconButton(onClick = { onEvent(KipotifyEvent.OnRemoveDownload(track.id)) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete Offline",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun PlaylistsTab(
    state: KipotifyUiState,
    onEvent: (KipotifyEvent) -> Unit,
    onCategorySelect: (String) -> Unit
) {
    val genres = listOf(
        Pair("موسیقی سنتی", Color(0xFFB45309)),
        Pair("سنتور نوازی", Color(0xFF047857)),
        Pair("Synthwave", Color(0xFF701A75)),
        Pair("Ambient Piano", Color(0xFF1E1B4B)),
        Pair("Chill Beats", Color(0xFF0369A1)),
        Pair("Rock Legends", Color(0xFFBE123C))
    )

    Column(modifier = Modifier.fillMaxSize().padding(bottom = 80.dp)) {
        SectionHeader(title = stringResource(R.string.playlists_title))

        SectionHeader(title = stringResource(R.string.section_world_music), compact = true)

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 156.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(genres) { genre ->
                Card(
                    modifier = Modifier
                        .height(112.dp)
                        .shadowScale(),
                    shape = MaterialTheme.shapes.large,
                    onClick = { onCategorySelect(genre.first) }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(genre.second, genre.second.copy(alpha = 0.5f))
                                )
                            )
                            .padding(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.LibraryMusic,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.72f),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(28.dp)
                        )
                        Text(
                            text = genre.first,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.align(Alignment.BottomStart)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileTab(
    state: KipotifyUiState,
    onEvent: (KipotifyEvent) -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
            .padding(bottom = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Avatar Header
        Box(contentAlignment = Alignment.BottomEnd) {
            Image(
                painter = painterResource(id = R.drawable.img_app_icon),
                contentDescription = "Avatar",
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                contentScale = ContentScale.Crop
            )
            // Golden check badge for premium members
            if (state.isPremium) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(Color(0xFFF59E0B), CircleShape)
                        .border(1.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Check, contentDescription = "Premium Badge", tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.kipotify_user),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = if (state.isPremium) stringResource(R.string.premium_member) else stringResource(R.string.standard_member),
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.isPremium) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Become Premium action
        if (!state.isPremium) {
            Card(
                modifier = Modifier.fillMaxWidth().shadowScale(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                onClick = { onEvent(KipotifyEvent.OnUpgradePremium) }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(Color(0xFFF59E0B), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "Star", tint = Color.White)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.upgrade_to_premium),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Text(
                            stringResource(R.string.premium_required_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.78f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        // Settings Block
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.settings), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(12.dp))

                // Toggle Language
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.setting_language),
                        modifier = Modifier.weight(1f)
                    )
                    Row(
                        modifier = Modifier
                            .weight(1.4f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.End
                    ) {
                        FilterChip(
                            selected = state.language == "fa",
                            onClick = { onEvent(KipotifyEvent.OnSetLanguage("fa")) },
                            label = { Text("فارسی") }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        FilterChip(
                            selected = state.language == "en",
                            onClick = { onEvent(KipotifyEvent.OnSetLanguage("en")) },
                            label = { Text("English") }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Toggle Theme
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.setting_theme),
                        modifier = Modifier.weight(1f)
                    )
                    Row(
                        modifier = Modifier
                            .weight(1.4f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.End
                    ) {
                        FilterChip(
                            selected = state.theme == "system",
                            onClick = { onEvent(KipotifyEvent.OnSetTheme("system")) },
                            label = { Text(stringResource(R.string.theme_system)) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        FilterChip(
                            selected = state.theme == "dark",
                            onClick = { onEvent(KipotifyEvent.OnSetTheme("dark")) },
                            label = { Text(stringResource(R.string.theme_dark)) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        FilterChip(
                            selected = state.theme == "light",
                            onClick = { onEvent(KipotifyEvent.OnSetTheme("light")) },
                            label = { Text(stringResource(R.string.theme_light)) }
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// DETAILED LIST SCREEN
// ==========================================

@Composable
fun CategoryDetailView(
    categoryName: String,
    state: KipotifyUiState,
    onEvent: (KipotifyEvent) -> Unit,
    onBack: () -> Unit
) {
    // Determine track set
    val tracks = when (categoryName) {
        "Liked" -> state.tracks.filter { it.isLiked }
        "Recent" -> state.tracks.take(6)
        "Artists" -> state.tracks.filter { it.id.hashCode() % 3 == 0 }
        "User" -> state.tracks.filter { it.id.hashCode() % 5 == 0 }
        else -> state.tracks.filter { it.id.hashCode() % 4 == 0 }
    }

    Column(modifier = Modifier.fillMaxSize().padding(bottom = 80.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(categoryName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        if (tracks.isEmpty()) {
            EmptyStateView(
                icon = Icons.Default.Favorite,
                message = stringResource(R.string.playlist_empty)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                items(tracks, key = { it.id }) { track ->
                    TrackListItem(
                        track = track,
                        onPlay = { onEvent(KipotifyEvent.OnPlayTrack(track, tracks)) },
                        onLike = { onEvent(KipotifyEvent.OnToggleLike(track.id)) }
                    )
                }
            }
        }
    }
}

// ==========================================
// MEDIA PLAYER DRAWING & SHELLS
// ==========================================

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocialChatHub(
    state: KipotifyUiState,
    onEvent: (KipotifyEvent) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = RoundedCornerShape(0.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
            modifier = Modifier.fillMaxSize()
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            if (state.activeChatFriend != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    AsyncImage(
                                        model = state.activeChatFriend.avatarUrl,
                                        contentDescription = "Chatting Avatar",
                                        modifier = Modifier.size(32.dp).clip(CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(state.activeChatFriend.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                        if (state.typingFriendId == state.activeChatFriend.id) {
                                            Text(stringResource(R.string.typing_text, state.activeChatFriend.name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                                        }
                                    }
                                }
                            } else {
                                Text(stringResource(R.string.social_tab), fontWeight = FontWeight.Bold)
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = {
                                if (state.activeChatFriend != null) {
                                    onEvent(KipotifyEvent.OnOpenChatWithFriend(null))
                                } else {
                                    onDismiss()
                                }
                            }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                            }
                        }
                    )
                }
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    if (state.activeChatFriend == null) {
                        // Friends List and search-following management
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item {
                                Text(
                                    text = stringResource(R.string.friends_list),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                            items(state.friends) { friend ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onEvent(KipotifyEvent.OnOpenChatWithFriend(friend)) }
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        AsyncImage(
                                            model = friend.avatarUrl,
                                            contentDescription = friend.name,
                                            modifier = Modifier.size(50.dp).clip(CircleShape)
                                        )
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Column {
                                            Text(friend.name, fontWeight = FontWeight.Bold)
                                            Text(
                                                stringResource(R.string.friend_playlist_label, friend.publicPlaylistName),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Button(
                                        onClick = { onEvent(KipotifyEvent.OnToggleFollow(friend.id)) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (friend.isFollowing) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primary,
                                            contentColor = if (friend.isFollowing) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimary
                                        )
                                    ) {
                                        Text(if (friend.isFollowing) stringResource(R.string.unfollow_btn) else stringResource(R.string.follow_btn))
                                    }
                                }
                            }
                        }
                    } else {
                        // Active Chat Screen with Messages and typing simulator
                        var chatInputText by remember { mutableStateOf("") }

                        Column(modifier = Modifier.fillMaxSize()) {
                            // Message stream list
                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                LazyColumn(
                                    contentPadding = PaddingValues(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(state.chatMessages) { msg ->
                                        val isMe = msg.senderId == "me"
                                        val alignment = if (isMe) Alignment.CenterEnd else Alignment.CenterStart
                                        val containerCol = if (isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                                        val labelCol = if (isMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

                                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
                                            Card(
                                                shape = MaterialTheme.shapes.large,
                                                colors = CardDefaults.cardColors(containerColor = containerCol),
                                                modifier = Modifier.widthIn(max = 280.dp)
                                            ) {
                                                Column(modifier = Modifier.padding(12.dp)) {
                                                    Text(msg.content, color = labelCol)
                                                    
                                                    // Shared Song Custom Mini Card inside dialog
                                                    if (msg.sharedTrack != null) {
                                                        Spacer(modifier = Modifier.height(8.dp))
                                                        Card(
                                                            shape = RoundedCornerShape(8.dp),
                                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                                            onClick = { onEvent(KipotifyEvent.OnPlayTrack(msg.sharedTrack, state.tracks)) }
                                                        ) {
                                                            Row(
                                                                modifier = Modifier.padding(8.dp),
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                SongArtworkImage(
                                                                    track = msg.sharedTrack,
                                                                    contentDescription = msg.sharedTrack.title,
                                                                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)),
                                                                    contentScale = ContentScale.Crop
                                                                )
                                                                Spacer(modifier = Modifier.width(8.dp))
                                                                Column {
                                                                    Text(msg.sharedTrack.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                                                    Text(
                                                                        msg.sharedTrack.artistName,
                                                                        style = MaterialTheme.typography.bodySmall,
                                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                        maxLines = 1
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }

                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Row(
                                                        modifier = Modifier.align(Alignment.End),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        val dateStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp))
                                                        Text(dateStr, style = MaterialTheme.typography.labelSmall, color = labelCol.copy(alpha = 0.6f))
                                                        
                                                        if (isMe) {
                                                            Spacer(modifier = Modifier.width(4.dp))
                                                            // Read state check marks
                                                            val checkIcon = when (msg.status) {
                                                                "sending" -> Icons.Default.Refresh
                                                                "sent" -> Icons.Default.Check
                                                                else -> Icons.Default.Check // read double ticks simulation
                                                            }
                                                            Icon(checkIcon, contentDescription = msg.status, tint = if (msg.status == "read") MaterialTheme.colorScheme.tertiary else labelCol.copy(alpha = 0.6f), modifier = Modifier.size(12.dp))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Dynamic Input controls row
                            Surface(
                                tonalElevation = 2.dp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .navigationBarsPadding()
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = chatInputText,
                                        onValueChange = { chatInputText = it },
                                        placeholder = { Text(stringResource(R.string.chat_placeholder)) },
                                        modifier = Modifier.weight(1f).testTag("chat_input"),
                                        shape = RoundedCornerShape(24.dp),
                                        singleLine = true
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    IconButton(
                                        onClick = {
                                            if (chatInputText.isNotBlank()) {
                                                onEvent(KipotifyEvent.OnSendMessage(chatInputText))
                                                chatInputText = ""
                                            }
                                        },
                                        modifier = Modifier.background(MaterialTheme.colorScheme.primary, CircleShape)
                                    ) {
                                        Icon(Icons.Default.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.onPrimary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// LIST ITEMS & COMPONENT UTILS
// ==========================================

@Composable
fun TrackListItem(
    track: Track,
    onPlay: () -> Unit,
    onLike: (() -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null
) {
    val shape = MaterialTheme.shapes.large

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)), shape)
            .clickable(onClick = onPlay)
            .padding(horizontal = 10.dp, vertical = 10.dp)
            .heightIn(min = 64.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TrackArtwork(track = track, modifier = Modifier.size(56.dp))
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                track.artistName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(
            modifier = Modifier.padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                trailingContent != null -> trailingContent()
                onLike != null -> {
                    val likeTint by animateColorAsState(
                        targetValue = if (track.isLiked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        label = "Like Tint"
                    )
                    IconButton(onClick = onLike) {
                        Icon(
                            imageVector = if (track.isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Like Song",
                            tint = likeTint
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyStateView(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    message: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = "Empty",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

fun formatTime(milliseconds: Long): String {
    val safeMilliseconds = milliseconds.coerceAtLeast(0L)
    val seconds = (safeMilliseconds / 1000) % 60
    val minutes = safeMilliseconds / (1000 * 60)
    return String.format("%d:%02d", minutes, seconds)
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Text(
        text = title,
        style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.padding(
            start = 16.dp,
            end = 16.dp,
            top = if (compact) 8.dp else 16.dp,
            bottom = if (compact) 8.dp else 12.dp
        )
    )
}

@Composable
fun TrackArtwork(
    track: Track,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        SongArtworkImage(
            track = track,
            contentDescription = track.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
private fun SongArtworkImage(
    track: Track,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit
) {
    val artworkModels = remember(
        track.artworkSource,
        track.audioUrl,
        track.localFilePath,
        track.coverImageUrl,
        track.fallbackArtworkUrl
    ) {
        buildList<Any> {
            track.embeddedArtwork()?.let(::add)
            track.coverImageUrl.takeIf(String::isNotBlank)?.let(::add)
            track.fallbackArtworkUrl
                .takeIf { it.isNotBlank() && it != track.coverImageUrl }
                ?.let(::add)
        }
    }
    var artworkIndex by remember(artworkModels) { mutableIntStateOf(0) }

    AsyncImage(
        model = artworkModels.getOrNull(artworkIndex),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        onError = {
            if (artworkIndex < artworkModels.lastIndex) artworkIndex += 1
        }
    )
}

@Composable
fun Modifier.shadowScale(): Modifier = this.shadow(
    elevation = 6.dp,
    shape = MaterialTheme.shapes.large,
    clip = false
)

// ==========================================
// CUSTOM SPLASH LOADING SCREEN
// ==========================================

@Composable
fun SplashScreen(onTimeout: () -> Unit) {
    var startAnimation by remember { mutableStateOf(false) }
    LaunchedEffect(key1 = true) {
        startAnimation = true
        delay(2200)
        onTimeout()
    }

    val alphaAnim by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "Splash Alpha"
    )

    val scaleAnim by animateFloatAsState(
        targetValue = if (startAnimation) 1.05f else 0.85f,
        animationSpec = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
        label = "Splash Scale"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "Pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Pulse Scale"
    )

    // Waves for music bar simulation on splash
    val waveHeight1 by infiniteTransition.animateFloat(
        initialValue = 15f,
        targetValue = 65f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Wave 1"
    )
    val waveHeight2 by infiniteTransition.animateFloat(
        initialValue = 25f,
        targetValue = 85f,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Wave 2"
    )
    val waveHeight3 by infiniteTransition.animateFloat(
        initialValue = 20f,
        targetValue = 75f,
        animationSpec = infiniteRepeatable(
            animation = tween(720, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Wave 3"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0F172A), Color(0xFF1E1B4B), Color(0xFF020617))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.graphicsLayer(
                alpha = alphaAnim,
                scaleX = scaleAnim,
                scaleY = scaleAnim
            )
        ) {
            // Pulse Glow Ring behind the logo
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(160.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(130.dp * pulseScale)
                        .background(Color(0xFF10B981).copy(alpha = 0.15f), CircleShape)
                )
                Box(
                    modifier = Modifier
                        .size(100.dp * pulseScale)
                        .background(Color(0xFF10B981).copy(alpha = 0.25f), CircleShape)
                )
                Image(
                    painter = painterResource(id = R.drawable.img_app_icon),
                    contentDescription = "Splash Logo",
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(3.dp, Color(0xFF10B981), RoundedCornerShape(16.dp))
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Kipotify",
                style = MaterialTheme.typography.displaySmall,
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Social Music Universe",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.72f),
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Beautiful music wave visualizer at the bottom of splash
            Row(
                modifier = Modifier.height(80.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                val heights = listOf(waveHeight1, waveHeight2, waveHeight3, waveHeight2 * 0.7f, waveHeight1 * 1.2f)
                heights.forEachIndexed { index, ht ->
                    Box(
                        modifier = Modifier
                            .width(6.dp)
                            .height(ht.dp)
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(Color(0xFF10B981), Color(0xFF34D399))
                                ),
                                shape = RoundedCornerShape(3.dp)
                            )
                    )
                }
            }
        }
    }
}
