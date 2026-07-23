package com.example.domain.usecase

import com.example.domain.repository.SearchHistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchHistoryUseCasesTest {
    @Test
    fun saveNormalizesQueryAndSkipsBlankInput() = runBlocking {
        val repository = RecordingSearchHistoryRepository()
        val saveSearch = SaveSearch(repository)

        saveSearch("  taylor swift  ").getOrThrow()
        saveSearch("   ").getOrThrow()

        assertEquals(listOf("taylor swift"), repository.savedQueries)
    }

    private class RecordingSearchHistoryRepository : SearchHistoryRepository {
        val savedQueries = mutableListOf<String>()

        override fun observeRecentSearches(): Flow<List<String>> = flowOf(emptyList())

        override suspend fun save(query: String): Result<Unit> = runCatching {
            savedQueries += query
        }

        override suspend fun delete(query: String): Result<Unit> = Result.success(Unit)

        override suspend fun clear(): Result<Unit> = Result.success(Unit)
    }
}
