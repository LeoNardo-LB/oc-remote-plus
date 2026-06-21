package dev.leonardo.ocremotev2.domain.usecase

import dev.leonardo.ocremotev2.domain.model.CreateSessionOpts
import dev.leonardo.ocremotev2.domain.model.Session
import dev.leonardo.ocremotev2.domain.repository.SessionRepository
import javax.inject.Inject

/**
 * Use case: create a new session on a server.
 * Delegates to [SessionRepository.createSession].
 */
class CreateSessionUseCase @Inject constructor(
    private val sessionRepository: SessionRepository
) {
    suspend operator fun invoke(serverId: String, opts: CreateSessionOpts): Result<Session> {
        return sessionRepository.createSession(serverId, opts)
    }
}
