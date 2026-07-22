package com.example.data.repository

import com.example.data.local.daos.MessageDao
import com.example.data.local.entities.MessageEntity
import com.example.data.model.Friend
import com.example.data.model.Message
import com.example.data.model.Track
import com.example.data.remote.KipotifyApiClient
import com.example.data.remote.KipotifyApiService
import com.example.data.remote.SendMessageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class SocialRepository(
    private val api: KipotifyApiService,
    private val messageDao: MessageDao
) {
    private var activeFriendId: String? = null

    private val _friends = MutableStateFlow<List<Friend>>(emptyList())
    val friends: StateFlow<List<Friend>> = _friends.asStateFlow()

    private val _typingFriendId = MutableStateFlow<String?>(null)
    val typingFriendId: StateFlow<String?> = _typingFriendId.asStateFlow()

    fun getMessagesFlow(): Flow<List<Message>> {
        return messageDao.getAllMessages().map { entities ->
            entities.mapNotNull { entity ->
                runCatching {
                    val sharedTrack = entity.sharedTrackJson?.let { jsonStr ->
                        KipotifyApiClient.json.decodeFromString(Track.serializer(), jsonStr)
                    }?.let(::normalizeTrackUrls)
                    Message(
                        id = entity.id,
                        senderId = entity.senderId,
                        senderName = entity.senderName,
                        content = entity.content,
                        timestamp = entity.timestamp,
                        status = entity.status,
                        sharedTrack = sharedTrack
                    )
                }.getOrNull()
            }
        }
    }

    suspend fun refreshFriends(): Result<List<Friend>> = withContext(Dispatchers.IO) {
        runCatching {
            api.getFriends().also { _friends.value = it }
        }
    }

    suspend fun openChat(friend: Friend?) = withContext(Dispatchers.IO) {
        activeFriendId = friend?.id
        messageDao.clearMessages()
        if (friend != null) {
            refreshMessages(friend.id)
        }
    }

    suspend fun refreshMessages(friendId: String): Result<List<Message>> = withContext(Dispatchers.IO) {
        runCatching {
            val messages = api.getChatMessages(friendId)
                .asReversed()
                .map { normalizeMessage(it, friendId) }
            messageDao.clearMessages()
            messages.forEach { messageDao.insertMessage(it.toEntity()) }
            messages
        }
    }

    suspend fun toggleFollowFriend(friendId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val response = api.toggleFollowFriend(friendId)
            _friends.value = _friends.value.map { friend ->
                if (friend.id == friendId) friend.copy(isFollowing = response.isFollowing) else friend
            }
            response.isFollowing
        }
    }

    suspend fun sendMessage(content: String, sharedTrack: Track? = null): Result<Message> = withContext(Dispatchers.IO) {
        val friendId = activeFriendId ?: return@withContext Result.failure(IllegalStateException("No active chat"))
        runCatching {
            api.sendChatMessage(
                friendId = friendId,
                request = SendMessageRequest(content = content, sharedTrackId = sharedTrack?.id)
            ).let { normalizeMessage(it, friendId) }
                .also { messageDao.insertMessage(it.toEntity()) }
        }
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        messageDao.clearMessages()
    }

    private fun normalizeMessage(message: Message, friendId: String): Message {
        val isFromFriend = message.senderId == friendId
        val normalizedSharedTrack = (message.sharedTrack ?: message.songCard)?.let(::normalizeTrackUrls)
        return message.copy(
            senderId = if (isFromFriend) friendId else "me",
            sharedTrack = normalizedSharedTrack,
            songCard = null
        )
    }

    private fun Message.toEntity(): MessageEntity {
        return MessageEntity(
            id = id,
            senderId = senderId,
            senderName = senderName,
            content = content,
            timestamp = timestamp,
            status = status,
            sharedTrackJson = sharedTrack?.let { KipotifyApiClient.json.encodeToString(Track.serializer(), it) }
        )
    }

    private fun normalizeTrackUrls(track: Track): Track {
        return track.copy(
            coverImageUrl = absoluteMediaUrl(track.coverImageUrl),
            fallbackArtworkUrl = absoluteMediaUrl(track.fallbackArtworkUrl),
            audioUrl = absoluteMediaUrl(track.audioUrl),
            lyricsUrl = absoluteMediaUrl(track.lyricsUrl)
        )
    }

    private fun absoluteMediaUrl(value: String): String {
        return KipotifyApiClient.absoluteUrl(value)
    }
}
