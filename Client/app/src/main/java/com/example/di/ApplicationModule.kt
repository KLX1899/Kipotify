package com.example.di

import android.content.Context
import com.example.domain.repository.AccountRepository
import com.example.domain.repository.PlaybackController
import com.example.domain.repository.LyricsRepository
import com.example.domain.repository.SearchHistoryRepository
import com.example.domain.repository.SocialRepository
import com.example.domain.repository.TrackRepository
import com.example.domain.usecase.AccountUseCases
import com.example.domain.usecase.SearchHistoryUseCases
import com.example.domain.usecase.LyricsUseCases
import com.example.domain.usecase.SocialUseCases
import com.example.domain.usecase.TrackUseCases
import com.example.playback.AudioPlayerManager
import com.example.playback.artwork.EmbeddedArtworkLoader
import androidx.media3.exoplayer.ExoPlayer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ApplicationModule {

    @Provides
    @Singleton
    fun provideEmbeddedArtworkLoader(
        @ApplicationContext context: Context,
    ): EmbeddedArtworkLoader = EmbeddedArtworkLoader(context)

    @Provides
    @Singleton
    fun provideExoPlayer(
        @ApplicationContext context: Context,
    ): ExoPlayer {
        val playbackContext = if (
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
        ) {
            context.createAttributionContext("media")
        } else {
            context
        }
        return ExoPlayer.Builder(playbackContext).build()
    }

    @Provides
    @Singleton
    fun providePlaybackController(
        exoPlayer: ExoPlayer,
        embeddedArtworkLoader: EmbeddedArtworkLoader,
    ): PlaybackController = AudioPlayerManager(exoPlayer, embeddedArtworkLoader)

    @Provides
    fun provideTrackUseCases(
        trackRepository: TrackRepository,
        accountRepository: AccountRepository,
    ): TrackUseCases = TrackUseCases(trackRepository, accountRepository)

    @Provides
    fun provideLyricsUseCases(
        lyricsRepository: LyricsRepository,
    ): LyricsUseCases = LyricsUseCases(lyricsRepository)

    @Provides
    fun provideSocialUseCases(
        socialRepository: SocialRepository,
    ): SocialUseCases = SocialUseCases(socialRepository)

    @Provides
    fun provideAccountUseCases(
        accountRepository: AccountRepository,
    ): AccountUseCases = AccountUseCases(accountRepository)

    @Provides
    fun provideSearchHistoryUseCases(
        searchHistoryRepository: SearchHistoryRepository,
    ): SearchHistoryUseCases = SearchHistoryUseCases(searchHistoryRepository)
}
