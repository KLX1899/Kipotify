package com.example.data.repository

import com.example.data.local.daos.SearchHistoryDao
import com.example.data.local.entities.SearchHistoryEntity
import com.example.domain.repository.SearchHistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SearchHistoryRepositoryImpl(
    private val dao: SearchHistoryDao,
) : SearchHistoryRepository {
    override fun observeRecentSearches(): Flow<List<String>> =
        dao.getRecentSearchHistory().map { entries -> entries.map { it.query } }

    override suspend fun save(query: String): Result<Unit> = runCatching {
        if (query.isNotBlank()) dao.insertSearch(SearchHistoryEntity(query))
    }

    override suspend fun delete(query: String): Result<Unit> = runCatching {
        dao.deleteSearch(query)
    }

    override suspend fun clear(): Result<Unit> = runCatching {
        dao.clearHistory()
    }
}
