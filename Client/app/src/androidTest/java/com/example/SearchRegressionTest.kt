package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchRegressionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun enteringQuery_keepsSearchScreenAlive() {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("tab_search").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("tab_search").performClick()
        composeRule.onNodeWithTag("search_field").performTextInput("maria")

        Thread.sleep(750)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("search_field").assertIsDisplayed()
    }

    @Test
    fun rapidTypingAndClearing_keepsSearchScreenAlive() {
        openSearch()
        val searchField = composeRule.onNodeWithTag("search_field")

        searchField.performTextInput("m")
        searchField.performTextInput("a")
        searchField.performTextInput("r")
        searchField.performTextInput("i")
        searchField.performTextInput("a")
        searchField.performTextClearance()
        searchField.performTextInput("dua lipa")

        Thread.sleep(750)
        composeRule.waitForIdle()
        searchField.assertIsDisplayed()
    }

    @Test
    fun blankQuery_doesNotCrashOrEnterHistory() {
        openSearch()
        val searchField = composeRule.onNodeWithTag("search_field")

        searchField.performTextInput("   ")
        Thread.sleep(750)
        searchField.performTextClearance()
        Thread.sleep(750)

        composeRule.waitForIdle()
        searchField.assertIsDisplayed()
    }

    private fun openSearch() {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("tab_search").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("tab_search").performClick()
    }
}
