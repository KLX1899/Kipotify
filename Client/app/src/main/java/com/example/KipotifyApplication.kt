package com.example

import android.app.Application
import com.example.data.local.KipotifyDatabase
import com.example.data.local.SettingsManager
import com.example.data.repository.SocialRepository
import com.example.data.repository.TrackRepository
import com.example.playback.AudioPlayerManager

class KipotifyApplication : Application() {
    lateinit var database: KipotifyDatabase
        private set

    lateinit var settingsManager: SettingsManager
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
        trackRepository = TrackRepository(
            context = this,
            likedSongDao = database.likedSongDao(),
            downloadedSongDao = database.downloadedSongDao()
        )
        socialRepository = SocialRepository(
            messageDao = database.messageDao(),
            trackRepository = trackRepository
        )
        audioPlayerManager = AudioPlayerManager(this)
    }
}
