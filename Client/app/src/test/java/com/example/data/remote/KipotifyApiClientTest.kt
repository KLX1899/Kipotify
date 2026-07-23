package com.example.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class KipotifyApiClientTest {
    @Test
    fun absoluteUrlPreservesAndroidContentUris() {
        assertEquals(
            "content://media/external/audio/media/42",
            KipotifyApiClient.absoluteUrl("content://media/external/audio/media/42")
        )
        assertEquals(
            "android.resource://com.example/raw/song",
            KipotifyApiClient.absoluteUrl("android.resource://com.example/raw/song")
        )
    }
}
