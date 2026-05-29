package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.repository.SessionRepository
import javax.inject.Inject

/**
 * Use case: delete a session by ID.
 * Delegates to [SessionRepository.deleteSession].
 */
class DeleteSessionUseCase @Inject constructor(
    private val sessionRepository: SessionRepository
) {
    suspend operator fun invoke(id: String): Result<Unit> {
        return sessionRepository.deleteSession(id)
    }
}
