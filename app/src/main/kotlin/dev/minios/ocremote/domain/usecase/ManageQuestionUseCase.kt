package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.model.QuestionState
import dev.minios.ocremote.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case: manage questions (observe + reply).
 * Used by Phase 2 ChatViewModel.
 */
class ManageQuestionUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    fun getQuestionsFlow(sessionId: String): Flow<List<QuestionState>> =
        chatRepository.getQuestionsFlow(sessionId)

    suspend fun replyQuestion(questionId: String, answer: String): Result<Boolean> =
        chatRepository.replyQuestion(questionId, answer)
}
