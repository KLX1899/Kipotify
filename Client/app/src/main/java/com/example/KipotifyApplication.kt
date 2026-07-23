package com.example

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.example.data.local.artwork.EmbeddedArtworkFetcher
import com.example.data.local.artwork.EmbeddedArtworkKeyer
import com.example.data.local.artwork.EmbeddedArtworkLoader
import com.example.data.local.KipotifyDatabase
import com.example.data.local.SettingsManager
import com.example.data.remote.KipotifyApiClient
import com.example.data.remote.KipotifyApiService
import com.example.data.remote.BackendEndpointRegistry
import com.example.data.remote.LanBackendDiscovery
import com.example.data.repository.AuthRepository
import com.example.data.repository.SocialRepository
import com.example.data.repository.TrackRepository
import com.example.playback.AudioPlayerManager

class KipotifyApplication : Application(), ImageLoaderFactory {
    lateinit var database: KipotifyDatabase
        private set

    lateinit var settingsManager: SettingsManager
        private set

    lateinit var apiService: KipotifyApiService
        private set

    lateinit var backendDiscovery: LanBackendDiscovery
        private set

    lateinit var authRepository: AuthRepository
        private set

    lateinit var trackRepository: TrackRepository
        private set

    lateinit var socialRepository: SocialRepository
        private set

    lateinit var audioPlayerManager: AudioPlayerManager
        private set

    val embeddedArtworkLoader by lazy { EmbeddedArtworkLoader(this) }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(EmbeddedArtworkKeyer())
                add(EmbeddedArtworkFetcher.Factory(embeddedArtworkLoader))
            }
            .crossfade(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        database = KipotifyDatabase.getDatabase(this)
        settingsManager = SettingsManager(this)
        val endpointRegistry = BackendEndpointRegistry(settingsManager)
        apiService = KipotifyApiClient.create(settingsManager, endpointRegistry)
        backendDiscovery = LanBackendDiscovery(this, endpointRegistry).also { it.start() }
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
        audioPlayerManager = AudioPlayerManager(attributionContext, embeddedArtworkLoader)
    }
}
