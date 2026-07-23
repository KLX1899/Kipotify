package com.example.data.repository

import com.example.data.local.daos.MessageDao
import com.example.data.local.entities.MessageEntity
import com.example.data.remote.KipotifyApiClient
import com.example.data.remote.KipotifyApiService
import com.example.data.remote.SendMessageRequest
import com.example.domain.model.Friend
import com.example.domain.model.Message
import com.example.domain.model.Track
import com.example.domain.repository.SocialRepository
import kotlinx.serialization.Serializable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class SocialRepositoryImpl(
    private val api: KipotifyApiService,
    private val messageDao: MessageDao
) : SocialRepository {
    private var activeFriendId: String? = null

    private val _friends = MutableStateFlow<List<Friend>>(emptyList())
    override val friends: StateFlow<List<Friend>> = _friends.asStateFlow()

    private val _typingFriendId = MutableStateFlow<String?>(null)
    override val typingFriendId: StateFlow<String?> = _typingFriendId.asStateFlow()

    override fun observeMessages(): Flow<List<Message>> {
        return messageDao.getAllMessages().map { entities ->
            entities.mapNotNull { entity ->
                runCatching {
                    val sharedTrack = entity.sharedTrackJson?.let { jsonStr ->
                        KipotifyApiClient.json.decodeFromString<CachedTrack>(jsonStr).toDomain()
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

    override suspend fun refreshFriends(): Result<List<Friend>> = withContext(Dispatchers.IO) {
        runCatching {
            api.getFriends().map { it.toDomain() }.also { _friends.value = it }
        }
    }

    override suspend fun openChat(friend: Friend?) = withContext(Dispatchers.IO) {
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
                .map { normalizeMessage(it.toDomain(), friendId) }
            messageDao.clearMessages()
            messages.forEach { messageDao.insertMessage(it.toEntity()) }
            messages
        }
    }

    override suspend fun toggleFollowFriend(friendId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val response = api.toggleFollowFriend(friendId)
            _friends.value = _friends.value.map { friend ->
                if (friend.id == friendId) friend.copy(isFollowing = response.isFollowing) else friend
            }
            response.isFollowing
        }
    }

    override suspend fun sendMessage(content: String, sharedTrack: Track?): Result<Message> = withContext(Dispatchers.IO) {
        val friendId = activeFriendId ?: return@withContext Result.failure(IllegalStateException("No active chat"))
        runCatching {
            api.sendChatMessage(
                friendId = friendId,
                request = SendMessageRequest(content = content, sharedTrackId = sharedTrack?.id)
            ).let { normalizeMessage(it.toDomain(), friendId) }
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
            sharedTrackJson = sharedTrack?.let {
                KipotifyApiClient.json.encodeToString(CachedTrack.serializer(), CachedTrack.fromDomain(it))
            }
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

    @Serializable
    private data class CachedTrack(
        val id: String,
        val title: String,
        val artistName: String,
        val coverImageUrl: String,
        val fallbackArtworkUrl: String,
        val audioUrl: String,
        val lyricsUrl: String,
        val durationSeconds: Int,
        val localFilePath: String?,
    ) {
        fun toDomain() = Track(
            id = id,
            title = title,
            artistName = artistName,
            coverImageUrl = coverImageUrl,
            fallbackArtworkUrl = fallbackArtworkUrl,
            audioUrl = audioUrl,
            lyricsUrl = lyricsUrl,
            durationSeconds = durationSeconds,
            localFilePath = localFilePath,
        )

        companion object {
            fun fromDomain(track: Track) = CachedTrack(
                id = track.id,
                title = track.title,
                artistName = track.artistName,
                coverImageUrl = track.coverImageUrl,
                fallbackArtworkUrl = track.fallbackArtworkUrl,
                audioUrl = track.audioUrl,
                lyricsUrl = track.lyricsUrl,
                durationSeconds = track.durationSeconds,
                localFilePath = track.localFilePath,
            )
        }
    }
}
