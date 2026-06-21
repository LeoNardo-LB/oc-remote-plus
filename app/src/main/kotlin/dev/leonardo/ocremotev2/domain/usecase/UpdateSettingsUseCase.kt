package dev.leonardo.ocremotev2.domain.usecase

import dev.leonardo.ocremotev2.domain.model.AppSettings
import dev.leonardo.ocremotev2.domain.repository.SettingsRepository
import javax.inject.Inject

/**
 * Use case: update application settings.
 * Delegates to [SettingsRepository.updateSettings].
 */
class UpdateSettingsUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    suspend operator fun invoke(settings: AppSettings): Result<Unit> {
        return settingsRepository.updateSettings(settings)
    }
}
