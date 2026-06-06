package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.MessageWithParts
import dev.minios.ocremote.domain.repository.ChatRepository
import dev.minios.ocremote.domain.repository.SessionRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class MessagePaginationUseCase @Inject constructor(
    private val chatRepository: ChatRepository,
    private val sessionRepository: SessionRepository
) {
    fun observeMessages(sessionId: String): Flow<List<Message>> =
        chatRepository.getMessagesFlow(sessionId)

    suspend fun loadOlderMessages(serverId: String, sessionId: String, limit: Int): Result<List<MessageWithParts>> =
        sessionRepository.listMessages(serverId, sessionId, limit)
}
