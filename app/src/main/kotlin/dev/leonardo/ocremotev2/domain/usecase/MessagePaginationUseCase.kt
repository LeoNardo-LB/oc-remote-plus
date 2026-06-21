package dev.leonardo.ocremotev2.domain.usecase

import dev.leonardo.ocremotev2.domain.model.Message
import dev.leonardo.ocremotev2.domain.model.MessageWithParts
import dev.leonardo.ocremotev2.domain.repository.ChatRepository
import dev.leonardo.ocremotev2.domain.repository.SessionRepository
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
