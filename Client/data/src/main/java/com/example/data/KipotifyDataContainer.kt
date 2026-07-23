package com.example.data

import android.content.Context
import com.example.data.local.KipotifyDatabase
import com.example.data.local.SettingsManager
import com.example.data.remote.BackendEndpointRegistry
import com.example.data.remote.KipotifyApiClient
import com.example.data.remote.LanBackendDiscovery
import com.example.data.repository.AccountRepositoryImpl
import com.example.data.repository.SearchHistoryRepositoryImpl
import com.example.data.repository.SocialRepositoryImpl
import com.example.data.repository.TrackRepositoryImpl
import com.example.domain.repository.AccountRepository
import com.example.domain.repository.ConnectionRepository
import com.example.domain.repository.SearchHistoryRepository
import com.example.domain.repository.SocialRepository
import com.example.domain.repository.TrackRepository

/**
 * Data-layer composition root. Android storage and transport details stay inside the data
 * module; callers receive only domain repository contracts.
 */
class KipotifyDataContainer(context: Context) {
    private val applicationContext = context.applicationContext
    private val database = KipotifyDatabase.getDatabase(applicationContext)
    private val settings = SettingsManager(applicationContext)
    private val endpointRegistry = BackendEndpointRegistry(settings)
    private val api = KipotifyApiClient.create(settings, endpointRegistry)
    private val discovery = LanBackendDiscovery(applicationContext, endpointRegistry).also { it.start() }

    val accountRepository: AccountRepository = AccountRepositoryImpl(api, settings)
    val trackRepository: TrackRepository = TrackRepositoryImpl(
        context = applicationContext,
        api = api,
        likedSongDao = database.likedSongDao(),
        downloadedSongDao = database.downloadedSongDao(),
    )
    val socialRepository: SocialRepository = SocialRepositoryImpl(api, database.messageDao())
    val searchHistoryRepository: SearchHistoryRepository =
        SearchHistoryRepositoryImpl(database.searchHistoryDao())
    val connectionRepository: ConnectionRepository = discovery
}
