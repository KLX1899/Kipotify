package com.example.data.repository

import com.example.data.local.daos.MessageDao
import com.example.data.local.entities.MessageEntity
import com.example.data.model.Friend
import com.example.data.model.Message
import com.example.data.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.UUID

class SocialRepository(
    private val messageDao: MessageDao,
    private val trackRepository: TrackRepository
) {
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    // Initial mock friends with public vibe playlists
    private val _friends = MutableStateFlow<List<Friend>>(emptyList())
    val friends: StateFlow<List<Friend>> = _friends.asStateFlow()

    private val _typingFriendId = MutableStateFlow<String?>(null)
    val typingFriendId: StateFlow<String?> = _typingFriendId.asStateFlow()

    init {
        // Prepare some initial friends
        coroutineScope.launch {
            trackRepository.getTracksFlow().first().let { allTracks ->
                _friends.value = listOf(
                    Friend(
                        id = "friend_1",
                        name = "ماهان",
                        avatarUrl = "https://images.unsplash.com/photo-1535713875002-d1d0cf377fde?w=150",
                        isFollowing = true,
                        publicPlaylistName = "کلاسیک آرام",
                        publicTracks = allTracks.filter { it.artistName in listOf("Chopin", "Vivaldi", "Ludovico Einaudi") }
                    ),
                    Friend(
                        id = "friend_2",
                        name = "صبا",
                        avatarUrl = "https://images.unsplash.com/photo-1494790108377-be9c29b29330?w=150",
                        isFollowing = false,
                        publicPlaylistName = "پاپ شاد",
                        publicTracks = allTracks.filter { it.artistName in listOf("Adele", "Coldplay", "Dua Lipa") }
                    ),
                    Friend(
                        id = "friend_3",
                        name = "کیان",
                        avatarUrl = "https://images.unsplash.com/photo-1599566150163-29194dcaad36?w=150",
                        isFollowing = true,
                        publicPlaylistName = "رپ و هیپ‌هاپ",
                        publicTracks = allTracks.filter { it.artistName in listOf("یاس", "سوگند", "Daft Punk") }
                    ),
                    Friend(
                        id = "friend_4",
                        name = "سارا",
                        avatarUrl = "https://images.unsplash.com/photo-1438761681033-6461ffad8d80?w=150",
                        isFollowing = false,
                        publicPlaylistName = "موزیک پیانو",
                        publicTracks = allTracks.filter { it.artistName in listOf("Chopin", "Ludovico Einaudi") }
                    )
                )
            }
        }
    }

    fun getMessagesFlow(): Flow<List<Message>> {
        return messageDao.getAllMessages().map { entities ->
            entities.map { entity ->
                val track = entity.sharedTrackJson?.let { jsonStr ->
                    try {
                        Json.decodeFromString<Track>(jsonStr)
                    } catch (e: Exception) {
                        null
                    }
                }
                Message(
                    id = entity.id,
                    senderId = entity.senderId,
                    senderName = entity.senderName,
                    content = entity.content,
                    timestamp = entity.timestamp,
                    status = entity.status,
                    sharedTrack = track
                )
            }
        }
    }

    suspend fun toggleFollowFriend(friendId: String) {
        _friends.update { list ->
            list.map { friend ->
                if (friend.id == friendId) {
                    friend.copy(isFollowing = !friend.isFollowing)
                } else {
                    friend
                }
            }
        }
    }

    suspend fun sendMessage(content: String, sharedTrack: Track? = null) = withContext(Dispatchers.IO) {
        val messageId = UUID.randomUUID().toString()
        val trackJson = sharedTrack?.let { Json.encodeToString(Track.serializer(), it) }
        
        // 1. Insert message as "sending" (clock status)
        val initialEntity = MessageEntity(
            id = messageId,
            senderId = "me",
            senderName = "من",
            content = content,
            timestamp = System.currentTimeMillis(),
            status = "sending",
            sharedTrackJson = trackJson
        )
        messageDao.insertMessage(initialEntity)

        // Simulate WebSocket delivery pipeline
        coroutineScope.launch {
            // "sending" -> "sent" (single check tick)
            delay(800)
            messageDao.updateMessageStatus(messageId, "sent")

            // "sent" -> "read" (double check ticks)
            delay(1500)
            messageDao.updateMessageStatus(messageId, "read")

            // Simulate intelligent friend response chat triggers
            delay(1000)
            val activeFriend = _friends.value.firstOrNull { it.isFollowing } ?: return@launch
            
            // Set Typing Indicator
            _typingFriendId.value = activeFriend.id
            delay(2500) // typing simulation delay
            _typingFriendId.value = null

            // Friend sends reply with localized content or shared tracks
            val replyId = UUID.randomUUID().toString()
            val hasShare = (1..100).random() > 40 // 60% chance they share a track!
            var responseTrackJson: String? = null
            var responseContent = "به به! عجب موزیک قشنگی فرستادی"
            
            if (hasShare && activeFriend.publicTracks.isNotEmpty()) {
                val sharedResponseTrack = activeFriend.publicTracks.random()
                responseTrackJson = Json.encodeToString(Track.serializer(), sharedResponseTrack)
                responseContent = "این آهنگ رو گوش کن، عاشقش میشی:"
            } else if (content.contains("سلام") || content.lowercase().contains("hello")) {
                responseContent = "سلام عزیزم! حالت چطوره؟ چه خبرا؟"
            } else if (content.contains("خوب") || content.lowercase().contains("fine")) {
                responseContent = "خداروشکر! بیا با هم یه موزیک باحال گوش کنیم."
            }

            val replyEntity = MessageEntity(
                id = replyId,
                senderId = activeFriend.id,
                senderName = activeFriend.name,
                content = responseContent,
                timestamp = System.currentTimeMillis(),
                status = "read", // Received replies are read by default
                sharedTrackJson = responseTrackJson
            )
            messageDao.insertMessage(replyEntity)
        }
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        messageDao.clearMessages()
    }
}
