package com.example.data.remote

import com.example.domain.model.Friend
import com.example.domain.model.Message
import com.example.domain.model.Track
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import retrofit2.http.*

/**
 * Kipotify REST Api Service representing the client-to-backend interface declarations.
 * Following the instruction "write APIs for back-end (don't write back-end code)",
 * this file serves as the official definition of Kipotify's server communication architecture.
 */
interface KipotifyApiService {

    // --- Auth Endpoints ---
    @POST("api/auth/register")
    suspend fun register(@Body request: RegisterRequest): AuthResponse

    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest): AuthResponse

    // --- Tracks & Playlists Endpoints ---
    @GET("api/tracks")
    suspend fun getTracks(
        @Query("genre") genre: String? = null,
        @Query("search") search: String? = null
    ): List<ApiTrackDto>

    @GET("api/tracks/{id}")
    suspend fun getTrackById(@Path("id") trackId: String): ApiTrackDto

    @POST("api/tracks/{id}/like")
    suspend fun toggleLikeTrack(@Path("id") trackId: String): LikeResponse

    @POST("api/tracks/{id}/download")
    suspend fun logTrackDownload(@Path("id") trackId: String): DownloadLogResponse

    @POST("api/tracks/{id}/play")
    suspend fun recordTrackPlay(@Path("id") trackId: String): SuccessResponse

    @GET("api/downloads/eligibility/{trackId}")
    suspend fun getDownloadEligibility(@Path("trackId") trackId: String): DownloadEligibilityResponse

    // --- User & Premium Subscription Endpoints ---
    @GET("api/user/profile")
    suspend fun getUserProfile(): UserProfileResponse

    @POST("api/user/premium/upgrade")
    suspend fun upgradeToPremium(): PremiumUpgradeResponse

    @GET("api/user/settings")
    suspend fun getUserSettings(): UserSettingsResponse

    @PUT("api/user/settings")
    suspend fun updateUserSettings(@Body settings: UserSettingsRequest): SettingsUpdateResponse

    // --- Social & Chat Endpoints ---
    @GET("api/social/friends")
    suspend fun getFriends(): List<FriendDto>

    @POST("api/social/friends/{id}/follow")
    suspend fun toggleFollowFriend(@Path("id") friendId: String): FollowResponse

    @GET("api/social/chat/{friendId}/messages")
    suspend fun getChatMessages(
        @Path("friendId") friendId: String,
        @Query("limit") limit: Int = 50,
        @Query("before") beforeTimestamp: Long? = null
    ): List<MessageDto>

    @POST("api/social/chat/{friendId}/messages")
    suspend fun sendChatMessage(
        @Path("friendId") friendId: String,
        @Body request: SendMessageRequest
    ): MessageDto
}

// --- Request and Response Data Models ---

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ApiTrackDto(
    val id: String,
    @JsonNames("name")
    val title: String = "",
    val slug: String = "",
    @JsonNames("artist_id")
    val artistId: String = "",
    @JsonNames("artist", "artist_name")
    val artistName: String = "",
    @JsonNames("album_id")
    val albumId: String = "",
    @JsonNames("album", "album_title")
    val albumTitle: String = "",
    @JsonNames("release_id")
    val releaseId: String = "",
    @JsonNames("release_title")
    val releaseTitle: String = "",
    @JsonNames("release_type")
    val releaseType: String = "",
    @JsonNames("coverUrl", "cover_image_url", "cover_url", "artworkUrl", "artwork_url")
    val coverImageUrl: String = "",
    @JsonNames("fallback_artwork_url")
    val fallbackArtworkUrl: String = "",
    @JsonNames("streamUrl", "fileUrl", "audio_url", "stream_url", "file_url")
    val audioUrl: String = "",
    @JsonNames("audio_file_path")
    val audioFilePath: String = "",
    @JsonNames("lyrics_url")
    val lyricsUrl: String = "",
    @JsonNames("lyrics_file_path")
    val lyricsFilePath: String = "",
    @JsonNames("artwork_source")
    val artworkSource: String = "",
    val genre: String = "",
    val locale: String = "",
    @JsonNames("track_number")
    val trackNumber: Int = 0,
    @JsonNames("disc_number")
    val discNumber: Int = 1,
    @JsonNames("duration", "duration_seconds")
    val durationSeconds: Int = 0,
    val lyric: String = "",
    @JsonNames("release_date")
    val releaseDate: String? = null,
    val explicit: Boolean = false,
    @JsonNames("featured_artists")
    val featuredArtists: List<String> = emptyList(),
    @JsonNames("play_count")
    val playCount: Int = 0,
    @JsonNames("download_count")
    val downloadCount: Int = 0,
    @JsonNames("is_liked")
    val isLiked: Boolean = false,
    @JsonNames("is_downloaded")
    val isDownloaded: Boolean = false,
    @JsonNames("local_file_path")
    val localFilePath: String? = null,
    @JsonNames("created_at")
    val createdAt: String = ""
) {
    fun toTrack(): Track {
        val resolvedAudioUrl = audioUrl.ifBlank { audioFilePath }
        val resolvedCoverUrl = coverImageUrl.ifBlank { fallbackArtworkUrl }
        return Track(
            id = id,
            title = title,
            slug = slug,
            artistId = artistId,
            artistName = artistName,
            albumId = albumId,
            albumTitle = albumTitle,
            releaseId = releaseId,
            releaseTitle = releaseTitle,
            releaseType = releaseType,
            coverImageUrl = resolvedCoverUrl,
            fallbackArtworkUrl = fallbackArtworkUrl.ifBlank { resolvedCoverUrl },
            audioUrl = resolvedAudioUrl,
            audioFilePath = audioFilePath,
            lyricsUrl = lyricsUrl.ifBlank { lyricsFilePath },
            lyricsFilePath = lyricsFilePath,
            artworkSource = artworkSource,
            genre = genre,
            locale = locale,
            trackNumber = trackNumber,
            discNumber = discNumber,
            durationSeconds = durationSeconds,
            lyric = lyric,
            releaseDate = releaseDate,
            explicit = explicit,
            featuredArtists = featuredArtists,
            playCount = playCount,
            downloadCount = downloadCount,
            isLiked = isLiked,
            isDownloaded = isDownloaded,
            localFilePath = localFilePath,
            createdAt = createdAt
        )
    }
}

@Serializable
data class FriendDto(
    val id: String,
    val name: String,
    val email: String = "",
    val avatarUrl: String = "",
    val isPremium: Boolean = false,
    val isFollowing: Boolean = false,
    val publicPlaylistName: String = "Vibe Zone",
    val followersCount: Int = 0,
    val followingCount: Int = 0,
    val publicTracks: List<ApiTrackDto> = emptyList(),
) {
    fun toDomain() = Friend(
        id = id,
        name = name,
        email = email,
        avatarUrl = avatarUrl,
        isPremium = isPremium,
        isFollowing = isFollowing,
        publicPlaylistName = publicPlaylistName,
        followersCount = followersCount,
        followingCount = followingCount,
        publicTracks = publicTracks.map(ApiTrackDto::toTrack),
    )
}

@Serializable
data class MessageDto(
    val id: String,
    val senderId: String,
    val senderName: String,
    val receiverId: String = "",
    val content: String,
    val timestamp: Long,
    val status: String,
    val sharedTrack: ApiTrackDto? = null,
    val songCard: ApiTrackDto? = null,
    val deliveredAt: String? = null,
    val readAt: String? = null,
) {
    fun toDomain() = Message(
        id = id,
        senderId = senderId,
        senderName = senderName,
        receiverId = receiverId,
        content = content,
        timestamp = timestamp,
        status = status,
        sharedTrack = sharedTrack?.toTrack(),
        songCard = songCard?.toTrack(),
        deliveredAt = deliveredAt,
        readAt = readAt,
    )
}

@Serializable
data class RegisterRequest(
    val name: String,
    val email: String,
    val password: String
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)

@Serializable
data class AuthResponse(
    val token: String,
    val user: UserProfileResponse
)

@Serializable
data class LikeResponse(
    val trackId: String,
    val isLiked: Boolean
)

@Serializable
data class DownloadLogResponse(
    val trackId: String,
    val downloadCount: Int,
    val success: Boolean
)

@Serializable
data class UserProfileResponse(
    val userId: String = "",
    val id: String = "",
    val name: String,
    val email: String,
    val avatarUrl: String = "",
    val isPremium: Boolean,
    val premiumExpiresAt: String? = null,
    val language: String = "en",
    val theme: String = "system",
    val followersCount: Int = 0,
    val followingCount: Int = 0,
    val createdAt: String = ""
)

@Serializable
data class PremiumUpgradeResponse(
    val success: Boolean,
    val expiresAt: Long,
    val message: String
)

@Serializable
data class UserSettingsRequest(
    val language: String,
    val theme: String
)

@Serializable
data class UserSettingsResponse(
    val language: String = "en",
    val theme: String = "system"
)

@Serializable
data class SettingsUpdateResponse(
    val success: Boolean,
    val language: String,
    val theme: String
)

@Serializable
data class DownloadEligibilityResponse(
    val trackId: String,
    val canDownload: Boolean,
    val isPremium: Boolean
)

@Serializable
data class SuccessResponse(
    val success: Boolean
)

@Serializable
data class FollowResponse(
    val friendId: String,
    val isFollowing: Boolean
)

@Serializable
data class SendMessageRequest(
    val content: String,
    val sharedTrackId: String? = null
)
