package com.example.ui.feature.social

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
import java.util.Date
import java.util.Locale
import com.example.ui.components.SongArtworkImage


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
                                        val sharedTrack = msg.sharedTrack
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
                                                    if (sharedTrack != null) {
                                                        Spacer(modifier = Modifier.height(8.dp))
                                                        Card(
                                                            shape = RoundedCornerShape(8.dp),
                                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                                            onClick = { onEvent(KipotifyEvent.OnPlayTrack(sharedTrack, state.tracks)) }
                                                        ) {
                                                            Row(
                                                                modifier = Modifier.padding(8.dp),
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                SongArtworkImage(
                                                                    track = sharedTrack,
                                                                    contentDescription = sharedTrack.title,
                                                                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)),
                                                                    contentScale = ContentScale.Crop
                                                                )
                                                                Spacer(modifier = Modifier.width(8.dp))
                                                                Column {
                                                                    Text(sharedTrack.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                                                    Text(
                                                                        sharedTrack.artistName,
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
