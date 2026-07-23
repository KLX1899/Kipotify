package com.example

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.example.playback.artwork.EmbeddedArtworkFetcher
import com.example.playback.artwork.EmbeddedArtworkKeyer
import com.example.playback.artwork.EmbeddedArtworkLoader
import com.example.data.KipotifyDataContainer
import com.example.domain.repository.ConnectionRepository
import com.example.domain.repository.PlaybackController
import com.example.domain.usecase.AccountUseCases
import com.example.domain.usecase.SearchHistoryUseCases
import com.example.domain.usecase.SocialUseCases
import com.example.domain.usecase.TrackUseCases
import com.example.playback.AudioPlayerManager
import com.example.ui.viewmodel.KipotifyDependencies

class KipotifyApplication : Application(), ImageLoaderFactory, KipotifyDependencies {
    override lateinit var trackUseCases: TrackUseCases
        private set
    override lateinit var socialUseCases: SocialUseCases
        private set
    override lateinit var accountUseCases: AccountUseCases
        private set
    override lateinit var searchHistoryUseCases: SearchHistoryUseCases
        private set
    override lateinit var playbackController: PlaybackController
        private set
    override lateinit var connectionRepository: ConnectionRepository
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
        val data = KipotifyDataContainer(this)
        val attributionContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            createAttributionContext("media")
        } else {
            this
        }
        trackUseCases = TrackUseCases(data.trackRepository, data.accountRepository)
        socialUseCases = SocialUseCases(data.socialRepository)
        accountUseCases = AccountUseCases(data.accountRepository)
        searchHistoryUseCases = SearchHistoryUseCases(data.searchHistoryRepository)
        playbackController = AudioPlayerManager(attributionContext, embeddedArtworkLoader)
        connectionRepository = data.connectionRepository
    }
}
