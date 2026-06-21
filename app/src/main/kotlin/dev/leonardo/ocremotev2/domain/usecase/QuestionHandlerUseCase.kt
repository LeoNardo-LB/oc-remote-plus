package dev.leonardo.ocremotev2.domain.usecase

import dev.leonardo.ocremotev2.domain.model.QuestionState
import dev.leonardo.ocremotev2.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

// TODO: Add filtering/transformation logic or consider removing this UseCase if it remains a pure delegate
class QuestionHandlerUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    fun observeQuestions(sessionId: String): Flow<List<QuestionState>> =
        chatRepository.getQuestionsFlow(sessionId)
}
