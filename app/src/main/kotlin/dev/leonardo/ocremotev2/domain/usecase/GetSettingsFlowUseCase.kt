package dev.leonardo.ocremotev2.domain.usecase

import dev.leonardo.ocremotev2.domain.model.AppSettings
import dev.leonardo.ocremotev2.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case: observe application settings.
 * Used by Phase 4 SettingsViewModel.
 */
class GetSettingsFlowUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    operator fun invoke(): Flow<AppSettings> =
        settingsRepository.getSettingsFlow()
}
