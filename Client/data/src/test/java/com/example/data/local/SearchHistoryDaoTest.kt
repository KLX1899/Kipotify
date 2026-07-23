package com.example.data.local

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.entities.SearchHistoryEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SearchHistoryDaoTest {

    private lateinit var database: KipotifyDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, KipotifyDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun searchHistoryWrites_areSafeFromMainThread() = runBlocking {
        val dao = database.searchHistoryDao()

        dao.insertSearch(SearchHistoryEntity(query = "maria", timestamp = 1L))
        assertEquals(listOf("maria"), dao.getRecentSearchHistory().first().map { it.query })

        dao.deleteSearch("maria")
        assertEquals(emptyList<String>(), dao.getRecentSearchHistory().first().map { it.query })

        dao.insertSearch(SearchHistoryEntity(query = "dua lipa", timestamp = 2L))
        dao.clearHistory()
        assertEquals(emptyList<String>(), dao.getRecentSearchHistory().first().map { it.query })
    }
}
