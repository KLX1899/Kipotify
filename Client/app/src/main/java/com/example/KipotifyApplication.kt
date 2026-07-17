package com.example

import android.app.Application
import com.example.data.local.KipotifyDatabase
import com.example.data.local.SettingsManager
import com.example.data.remote.KipotifyApiClient
import com.example.data.remote.KipotifyApiService
import com.example.data.repository.AuthRepository
import com.example.data.repository.SocialRepository
import com.example.data.repository.TrackRepository
import com.example.playback.AudioPlayerManager

class KipotifyApplication : Application() {
    lateinit var database: KipotifyDatabase
        private set

    lateinit var settingsManager: SettingsManager
        private set

    lateinit var apiService: KipotifyApiService
        private set

    lateinit var authRepository: AuthRepository
        private set

    lateinit var trackRepository: TrackRepository
        private set

    lateinit var socialRepository: SocialRepository
        private set

    lateinit var audioPlayerManager: AudioPlayerManager
        private set

    override fun onCreate() {
        super.onCreate()
        database = KipotifyDatabase.getDatabase(this)
        settingsManager = SettingsManager(this)
        apiService = KipotifyApiClient.create(settingsManager)
        authRepository = AuthRepository(
            api = apiService,
            settingsManager = settingsManager
        )
        trackRepository = TrackRepository(
            context = this,
            api = apiService,
            likedSongDao = database.likedSongDao(),
            downloadedSongDao = database.downloadedSongDao()
        )
        socialRepository = SocialRepository(
            api = apiService,
            messageDao = database.messageDao()
        )
        val attributionContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            createAttributionContext("media")
        } else {
            this
        }
        audioPlayerManager = AudioPlayerManager(attributionContext)
    }
}
