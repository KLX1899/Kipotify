package com.example.ui.feature.player

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.example.domain.model.LyricLine
import com.example.ui.theme.KipotifyTheme
import com.example.ui.viewmodel.LyricsUiState
import org.junit.Rule
import org.junit.Test

class LyricsContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loadingStateIsDisplayed() {
        composeRule.setContent {
            KipotifyTheme {
                LyricsContent(
                    state = LyricsUiState.Loading,
                    playbackPositionMs = 0L,
                    onRetry = {},
                    onSeekTo = {},
                )
            }
        }
        composeRule.onNodeWithTag("lyrics_loading").assertIsDisplayed()
    }

    @Test
    fun unavailableStateIsDisplayed() {
        composeRule.setContent {
            KipotifyTheme {
                LyricsContent(
                    state = LyricsUiState.Unavailable,
                    playbackPositionMs = 0L,
                    onRetry = {},
                    onSeekTo = {},
                )
            }
        }
        composeRule.onNodeWithTag("lyrics_unavailable").assertIsDisplayed()
    }

    @Test
    fun lastLineAtOrBeforePlaybackPositionIsHighlighted() {
        composeRule.setContent {
            KipotifyTheme {
                LyricsContent(
                    state = LyricsUiState.Success(
                        listOf(
                            LyricLine(1_000L, "First line"),
                            LyricLine(2_000L, "Second line"),
                        ),
                    ),
                    playbackPositionMs = 2_000L,
                    onRetry = {},
                    onSeekTo = {},
                )
            }
        }

        composeRule.onNodeWithTag("active_lyric")
            .assertIsDisplayed()
            .assertTextEquals("Second line")
    }
}
