package com.example.data.repository

import com.example.data.local.SettingsManager
import com.example.data.remote.KipotifyApiService
import com.example.data.remote.LoginRequest
import com.example.data.remote.RegisterRequest
import com.example.data.remote.UserProfileResponse
import com.example.data.remote.UserSettingsRequest
import com.example.domain.model.UserProfile
import com.example.domain.repository.AccountRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

class AccountRepositoryImpl(
    private val api: KipotifyApiService,
    private val settingsManager: SettingsManager
) : AccountRepository {
    override val theme: StateFlow<String> = settingsManager.theme
    override val language: StateFlow<String> = settingsManager.language
    override val isPremium: StateFlow<Boolean> = settingsManager.isPremium

    suspend fun register(name: String, email: String, password: String): Result<UserProfile> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = api.register(RegisterRequest(name, email, password))
                settingsManager.setAuthToken(response.token)
                syncLocalProfile(response.user)
                response.user.toDomain()
            }
        }

    suspend fun login(email: String, password: String): Result<UserProfile> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = api.login(LoginRequest(email, password))
                settingsManager.setAuthToken(response.token)
                syncLocalProfile(response.user)
                response.user.toDomain()
            }
        }

    override suspend fun refreshProfile(): Result<UserProfile> = withContext(Dispatchers.IO) {
        runCatching {
            api.getUserProfile().also(::syncLocalProfile).toDomain()
        }
    }

    override suspend fun upgradePremium(): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            api.upgradeToPremium().success.also { upgraded ->
                if (upgraded) {
                    settingsManager.setPremium(true)
                    refreshProfile()
                }
            }
        }
    }

    override suspend fun updateSettings(language: String, theme: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            settingsManager.setLanguage(language)
            settingsManager.setTheme(theme)
            runCatching {
                api.updateUserSettings(UserSettingsRequest(language, theme))
                refreshProfile()
                Unit
            }
        }

    private fun syncLocalProfile(profile: UserProfileResponse) {
        settingsManager.setPremium(profile.isPremium)
        settingsManager.setLanguage(profile.language)
        settingsManager.setTheme(profile.theme)
    }

    private fun UserProfileResponse.toDomain() = UserProfile(
        id = userId.ifBlank { id },
        name = name,
        email = email,
        avatarUrl = avatarUrl,
        isPremium = isPremium,
        language = language,
        theme = theme,
    )
}
