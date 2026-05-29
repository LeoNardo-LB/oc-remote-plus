package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.repository.ChatRepository
import javax.inject.Inject

/**
 * Use case: send a message to a session.
 * Delegates to [ChatRepository.sendMessage].
 */
class SendMessageUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    suspend operator fun invoke(sessionId: String, parts: List<Part>): Result<Message> {
        return chatRepository.sendMessage(sessionId, parts)
    }
}
