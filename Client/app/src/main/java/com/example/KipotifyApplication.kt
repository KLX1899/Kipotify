package com.example

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.example.playback.artwork.EmbeddedArtworkFetcher
import com.example.playback.artwork.EmbeddedArtworkKeyer
import com.example.playback.artwork.EmbeddedArtworkLoader
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class KipotifyApplication : Application(), ImageLoaderFactory {
    @Inject
    lateinit var embeddedArtworkLoader: EmbeddedArtworkLoader

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(EmbeddedArtworkKeyer())
                add(EmbeddedArtworkFetcher.Factory(embeddedArtworkLoader))
            }
            .crossfade(true)
            .build()
    }

}
