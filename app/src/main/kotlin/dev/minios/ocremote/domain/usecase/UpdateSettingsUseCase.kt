package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.model.AppSettings
import dev.minios.ocremote.domain.repository.SettingsRepository
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
