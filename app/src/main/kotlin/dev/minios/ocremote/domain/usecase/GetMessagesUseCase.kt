package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.repository.ChatRepository
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
