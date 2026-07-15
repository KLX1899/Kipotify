package com.example.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Friend(
    val id: String,
    val name: String,
    val avatarUrl: String,
    val isFollowing: Boolean = false,
    val publicPlaylistName: String = "Vibe Zone",
    val publicTracks: List<Track> = emptyList()
)
