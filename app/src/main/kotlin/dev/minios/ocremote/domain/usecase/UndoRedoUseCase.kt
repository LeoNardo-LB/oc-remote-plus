package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.repository.ChatRepository
import javax.inject.Inject

/**
 * Use case: undo and redo messages (revert/unrevert sessions).
 * Delegates to ChatRepository.
 */
class UndoRedoUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    suspend fun revertSession(serverId: String, sessionId: String, messageId: String) {
        chatRepository.revertSession(serverId, sessionId, messageId).getOrThrow()
    }

    suspend fun unrevertSession(serverId: String, sessionId: String) {
        chatRepository.unrevertSession(serverId, sessionId).getOrThrow()
    }
}
