package com.example.domain.usecase

import com.example.domain.repository.AccountRepository

class ObserveAccountSettings(private val repository: AccountRepository) {
    operator fun invoke() = Triple(repository.theme, repository.language, repository.isPremium)
}

class RefreshProfile(private val repository: AccountRepository) {
    suspend operator fun invoke() = repository.refreshProfile()
}

class UpgradePremium(private val repository: AccountRepository) {
    suspend operator fun invoke() = repository.upgradePremium()
}

class UpdateAccountSettings(private val repository: AccountRepository) {
    suspend operator fun invoke(language: String, theme: String) =
        repository.updateSettings(language, theme)
}

data class AccountUseCases(
    val observeSettings: ObserveAccountSettings,
    val refreshProfile: RefreshProfile,
    val upgradePremium: UpgradePremium,
    val updateSettings: UpdateAccountSettings,
) {
    constructor(repository: AccountRepository) : this(
        ObserveAccountSettings(repository),
        RefreshProfile(repository),
        UpgradePremium(repository),
        UpdateAccountSettings(repository),
    )
}
