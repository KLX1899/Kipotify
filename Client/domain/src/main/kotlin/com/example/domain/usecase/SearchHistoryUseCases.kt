package com.example.domain.usecase

import com.example.domain.repository.SearchHistoryRepository

class ObserveSearchHistory(private val repository: SearchHistoryRepository) {
    operator fun invoke() = repository.observeRecentSearches()
}

class SaveSearch(private val repository: SearchHistoryRepository) {
    suspend operator fun invoke(query: String): Result<Unit> {
        val normalized = query.trim()
        return if (normalized.isEmpty()) Result.success(Unit) else repository.save(normalized)
    }
}

class DeleteSearch(private val repository: SearchHistoryRepository) {
    suspend operator fun invoke(query: String) = repository.delete(query)
}

class ClearSearchHistory(private val repository: SearchHistoryRepository) {
    suspend operator fun invoke() = repository.clear()
}

data class SearchHistoryUseCases(
    val observe: ObserveSearchHistory,
    val save: SaveSearch,
    val delete: DeleteSearch,
    val clear: ClearSearchHistory,
) {
    constructor(repository: SearchHistoryRepository) : this(
        ObserveSearchHistory(repository),
        SaveSearch(repository),
        DeleteSearch(repository),
        ClearSearchHistory(repository),
    )
}
