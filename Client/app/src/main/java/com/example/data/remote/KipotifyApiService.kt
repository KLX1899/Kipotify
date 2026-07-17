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
    ): List<Track>

    @GET("api/tracks/{id}")
    suspend fun getTrackById(@Path("id") trackId: String): Track

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
