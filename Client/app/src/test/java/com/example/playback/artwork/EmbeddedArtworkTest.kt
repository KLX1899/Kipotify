package com.example.playback.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedArtworkTest {
    @Test
    fun embeddedArtworkFlagEnablesRemoteAudioMetadata() {
        assertTrue(
            shouldLoadEmbeddedArtwork(
                artworkSource = "embedded_audio",
                localFilePath = null,
                audioUrl = "https://example.test/song.flac"
            )
        )
    }

    @Test
    fun contentAndLocalFilesEnableMetadataWithoutFlag() {
        assertTrue(shouldLoadEmbeddedArtwork("", null, "content://media/audio/42"))
        assertTrue(shouldLoadEmbeddedArtwork("", "/storage/music/song.m4a", ""))
        assertTrue(shouldLoadEmbeddedArtwork("", "file:///storage/music/song.ogg", ""))
    }

    @Test
    fun ordinaryRemoteTrackKeepsUrlArtworkPath() {
        assertFalse(
            shouldLoadEmbeddedArtwork(
                artworkSource = "",
                localFilePath = null,
                audioUrl = "https://example.test/song.mp3"
            )
        )
    }

    @Test
    fun serverArtworkEndpointAvoidsExtractingFromTheAudioStreamOnAndroid() {
        assertFalse(
            shouldLoadEmbeddedArtwork(
                artworkSource = "embedded_audio",
                localFilePath = null,
                audioUrl = "http://192.168.1.10:18080/media/audio/artist/song.mp3",
                coverImageUrl = "http://192.168.1.10:18080/media/artwork/artist/song.mp3"
            )
        )
    }

    @Test
    fun detectsCommonEmbeddedImageTypes() {
        assertEquals(
            "image/jpeg",
            embeddedArtworkMimeType(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
        )
        assertEquals(
            "image/png",
            embeddedArtworkMimeType(
                byteArrayOf(
                    0x89.toByte(), 0x50, 0x4E, 0x47,
                    0x0D, 0x0A, 0x1A, 0x0A
                )
            )
        )
        assertEquals(
            "image/webp",
            embeddedArtworkMimeType("RIFF1234WEBP".encodeToByteArray())
        )
        assertNull(embeddedArtworkMimeType(byteArrayOf(1, 2, 3)))
    }
}
