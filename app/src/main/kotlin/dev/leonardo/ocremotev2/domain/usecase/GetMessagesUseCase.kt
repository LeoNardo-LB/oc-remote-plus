package dev.leonardo.ocremotev2.domain.usecase

import dev.leonardo.ocremotev2.domain.model.Message
import dev.leonardo.ocremotev2.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case: observe messages for a session.
 * Used by Phase 2 ChatViewModel.
 */
class GetMessagesUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    operator fun invoke(sessionId: String): Flow<List<Message>> =
        chatRepository.getMessagesFlow(sessionId)
}
