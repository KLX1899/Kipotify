package com.example.data.repository

import com.example.data.local.SettingsManager
import com.example.data.remote.KipotifyApiService
import com.example.data.remote.LoginRequest
import com.example.data.remote.RegisterRequest
import com.example.data.remote.UserProfileResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AuthRepository(
    private val api: KipotifyApiService,
    private val settingsManager: SettingsManager
) {
    suspend fun register(name: String, email: String, password: String): Result<UserProfileResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = api.register(RegisterRequest(name, email, password))
                settingsManager.setAuthToken(response.token)
                syncLocalProfile(response.user)
                response.user
            }
        }

    suspend fun login(email: String, password: String): Result<UserProfileResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = api.login(LoginRequest(email, password))
                settingsManager.setAuthToken(response.token)
                syncLocalProfile(response.user)
                response.user
            }
        }

    suspend fun refreshProfile(): Result<UserProfileResponse> = withContext(Dispatchers.IO) {
        runCatching {
            api.getUserProfile().also(::syncLocalProfile)
        }
    }

    private fun syncLocalProfile(profile: UserProfileResponse) {
        settingsManager.setPremium(profile.isPremium)
        settingsManager.setLanguage(profile.language)
        settingsManager.setTheme(profile.theme)
    }
}
