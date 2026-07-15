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

    fun setFontSize(size: Float) {
        prefs.edit().putFloat("font_size", size).apply()
        _fontSize.value = size
    }
}
