package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.repository.SessionRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case: manage sessions (switch, observe).
 * Used by Phase 2 ChatViewModel.
 */
class ManageSessionUseCase @Inject constructor(
    private val sessionRepository: SessionRepository
) {
    fun getSessionsFlow(serverId: String): Flow<List<Session>> =
        sessionRepository.getSessionsFlow(serverId)

    suspend fun switchSession(sessionId: String): Result<Unit> =
        sessionRepository.switchSession(sessionId)
}
