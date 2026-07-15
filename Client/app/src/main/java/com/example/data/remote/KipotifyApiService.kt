package com.example.data.remote

import com.example.data.model.Track
import com.example.data.model.Friend
import com.example.data.model.Message
import kotlinx.serialization.Serializable
import retrofit2.http.*

/**
 * Kipotify REST Api Service representing the client-to-backend interface declarations.
 * Following the instruction "write APIs for back-end (don't write back-end code)",
 * this file serves as the official definition of Kipotify's server communication architecture.
 */
interface KipotifyApiService {

    // --- Tracks & Playlists Endpoints ---
    @GET("api/tracks")
    suspend fun getTracks(
        @Query("genre") genre: String? = null,
        @Query("search") search: String? = null
    ): List<Track>

    @GET("api/tracks/{id}")
    suspend fun getTrackById(@Path("id") trackId: String): Track

    @POST("api/tracks/{id}/like")
    suspend fun toggleLikeTrack(@Path("id") trackId: String): LikeResponse

    @POST("api/tracks/{id}/download")
    suspend fun logTrackDownload(@Path("id") trackId: String): DownloadLogResponse

    // --- User & Premium Subscription Endpoints ---
    @GET("api/user/profile")
    suspend fun getUserProfile(): UserProfileResponse

    @POST("api/user/premium/upgrade")
    suspend fun upgradeToPremium(): PremiumUpgradeResponse

    @PUT("api/user/settings")
    suspend fun updateUserSettings(@Body settings: UserSettingsRequest): SettingsUpdateResponse

    // --- Social & Chat Endpoints ---
    @GET("api/social/friends")
    suspend fun getFriends(): List<Friend>

    @POST("api/social/friends/{id}/follow")
    suspend fun toggleFollowFriend(@Path("id") friendId: String): FollowResponse

    @GET("api/social/chat/{friendId}/messages")
    suspend fun getChatMessages(
        @Path("friendId") friendId: String,
        @Query("limit") limit: Int = 50,
        @Query("before") beforeTimestamp: Long? = null
    ): List<Message>

    @POST("api/social/chat/{friendId}/messages")
    suspend fun sendChatMessage(
        @Path("friendId") friendId: String,
        @Body request: SendMessageRequest
    ): Message
}

// --- Request and Response Data Models ---

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
    val userId: String,
    val name: String,
    val email: String,
    val isPremium: Boolean,
    val language: String
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
data class SettingsUpdateResponse(
    val success: Boolean,
    val language: String,
    val theme: String
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
