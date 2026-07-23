package com.example.data.di

import android.content.Context
import com.example.data.local.KipotifyDatabase
import com.example.data.local.SettingsManager
import com.example.data.local.daos.DownloadedSongDao
import com.example.data.local.daos.LikedSongDao
import com.example.data.local.daos.MessageDao
import com.example.data.local.daos.SearchHistoryDao
import com.example.data.remote.BackendEndpointRegistry
import com.example.data.remote.KipotifyApiClient
import com.example.data.remote.KipotifyApiService
import com.example.data.remote.LanBackendDiscovery
import com.example.data.repository.AccountRepositoryImpl
import com.example.data.repository.LyricsRepositoryImpl
import com.example.data.repository.SearchHistoryRepositoryImpl
import com.example.data.repository.SocialRepositoryImpl
import com.example.data.repository.TrackRepositoryImpl
import com.example.domain.repository.AccountRepository
import com.example.domain.repository.ConnectionRepository
import com.example.domain.repository.LyricsRepository
import com.example.domain.repository.SearchHistoryRepository
import com.example.domain.repository.SocialRepository
import com.example.domain.repository.TrackRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): KipotifyDatabase = KipotifyDatabase.getDatabase(context)

    @Provides
    fun provideSearchHistoryDao(database: KipotifyDatabase): SearchHistoryDao =
        database.searchHistoryDao()

    @Provides
    fun provideLikedSongDao(database: KipotifyDatabase): LikedSongDao =
        database.likedSongDao()

    @Provides
    fun provideDownloadedSongDao(database: KipotifyDatabase): DownloadedSongDao =
        database.downloadedSongDao()

    @Provides
    fun provideMessageDao(database: KipotifyDatabase): MessageDao =
        database.messageDao()

    @Provides
    @Singleton
    fun provideSettingsManager(
        @ApplicationContext context: Context,
    ): SettingsManager = SettingsManager(context)

    @Provides
    @Singleton
    fun provideEndpointRegistry(
        settingsManager: SettingsManager,
    ): BackendEndpointRegistry = BackendEndpointRegistry(settingsManager)

    @Provides
    @Singleton
    fun provideApi(
        settingsManager: SettingsManager,
        endpointRegistry: BackendEndpointRegistry,
    ): KipotifyApiService = KipotifyApiClient.create(settingsManager, endpointRegistry)

    @Provides
    @Singleton
    fun provideLanBackendDiscovery(
        @ApplicationContext context: Context,
        endpointRegistry: BackendEndpointRegistry,
    ): LanBackendDiscovery = LanBackendDiscovery(context, endpointRegistry).also { it.start() }

    @Provides
    @Singleton
    fun provideConnectionRepository(
        discovery: LanBackendDiscovery,
    ): ConnectionRepository = discovery

    @Provides
    @Singleton
    fun provideAccountRepository(
        api: KipotifyApiService,
        settingsManager: SettingsManager,
    ): AccountRepository = AccountRepositoryImpl(api, settingsManager)

    @Provides
    @Singleton
    fun provideTrackRepository(
        @ApplicationContext context: Context,
        api: KipotifyApiService,
        likedSongDao: LikedSongDao,
        downloadedSongDao: DownloadedSongDao,
    ): TrackRepository = TrackRepositoryImpl(
        context = context,
        api = api,
        likedSongDao = likedSongDao,
        downloadedSongDao = downloadedSongDao,
    )

    @Provides
    @Singleton
    fun provideLyricsRepository(
        api: KipotifyApiService,
    ): LyricsRepository = LyricsRepositoryImpl(api)

    @Provides
    @Singleton
    fun provideSocialRepository(
        api: KipotifyApiService,
        messageDao: MessageDao,
    ): SocialRepository = SocialRepositoryImpl(api, messageDao)

    @Provides
    @Singleton
    fun provideSearchHistoryRepository(
        searchHistoryDao: SearchHistoryDao,
    ): SearchHistoryRepository = SearchHistoryRepositoryImpl(searchHistoryDao)
}
