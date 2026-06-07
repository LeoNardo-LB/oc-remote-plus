package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.model.ModelSelection
import dev.minios.ocremote.domain.model.PromptPart
import dev.minios.ocremote.domain.repository.ChatRepository
import javax.inject.Inject

/**
 * Use case: send messages to a session.
 * Delegates to ChatRepository.
 */
class SendMessageUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    suspend fun sendPrompt(
        serverId: String,
        sessionId: String,
        parts: List<PromptPart>,
        model: ModelSelection?,
        agent: String,
        variant: String?,
        directory: String?
    ) {
        chatRepository.promptAsync(
            serverId = serverId,
            sessionId = sessionId,
            parts = parts,
            model = model,
            agent = agent,
            variant = variant,
            directory = directory
        ).getOrThrow()
    }
}
