package com.example.domain.model

data class Friend(
    val id: String,
    val name: String,
    val email: String = "",
    val avatarUrl: String,
    val isPremium: Boolean = false,
    val isFollowing: Boolean = false,
    val publicPlaylistName: String = "Vibe Zone",
    val followersCount: Int = 0,
    val followingCount: Int = 0,
    val publicTracks: List<Track> = emptyList()
)
