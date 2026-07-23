package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("kipotify_settings", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(prefs.getString("theme", "system") ?: "system")
    val theme: StateFlow<String> = _theme.asStateFlow()

    private val _language = MutableStateFlow(prefs.getString("language", "en") ?: "en") // English as default
    val language: StateFlow<String> = _language.asStateFlow()

    private val _isPremium = MutableStateFlow(prefs.getBoolean("is_premium", false))
    val isPremium: StateFlow<Boolean> = _isPremium.asStateFlow()

    private val _authToken = MutableStateFlow(prefs.getString("auth_token", null))
    val authToken: StateFlow<String?> = _authToken.asStateFlow()

    private val _fontSize = MutableStateFlow(prefs.getFloat("font_size", 16f))
    val fontSize: StateFlow<Float> = _fontSize.asStateFlow()

    fun setTheme(themeValue: String) {
        prefs.edit().putString("theme", themeValue).apply()
        _theme.value = themeValue
    }

    fun setLanguage(langValue: String) {
        prefs.edit().putString("language", langValue).apply()
        _language.value = langValue
    }

    fun setPremium(premiumValue: Boolean) {
        prefs.edit().putBoolean("is_premium", premiumValue).apply()
        _isPremium.value = premiumValue
    }

    fun setAuthToken(token: String?) {
        prefs.edit().apply {
            if (token.isNullOrBlank()) {
                remove("auth_token")
            } else {
                putString("auth_token", token)
            }
        }.apply()
        _authToken.value = token?.takeIf { it.isNotBlank() }
    }

    fun getAuthToken(): String? = _authToken.value

    fun getLastLanBackend(): String? = prefs.getString("last_lan_backend", null)

    fun setLastLanBackend(baseUrl: String) {
        prefs.edit().putString("last_lan_backend", baseUrl).apply()
    }

    fun setFontSize(size: Float) {
        prefs.edit().putFloat("font_size", size).apply()
        _fontSize.value = size
    }
}
