package com.example.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Message(
    val id: String,
    val senderId: String,
    val senderName: String,
    val receiverId: String = "",
    val content: String,
    val timestamp: Long,
    val status: String, // "sending", "sent", "read"
    val sharedTrack: Track? = null,
    val songCard: Track? = null,
    val deliveredAt: String? = null,
    val readAt: String? = null
)
