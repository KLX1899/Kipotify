package com.example.domain.model

data class UserProfile(
    val id: String,
    val name: String,
    val email: String,
    val avatarUrl: String = "",
    val isPremium: Boolean = false,
    val language: String = "en",
    val theme: String = "system"
)
