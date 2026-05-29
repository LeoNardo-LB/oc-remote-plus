package dev.minios.ocremote.data.repository.handler

import dev.minios.ocremote.domain.model.SseEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles question events: asked, replied, rejected.
 * Manages: questions
 */
@Singleton
class QuestionEventHandler @Inject constructor() : SseEventHandler {

    private val _questions = MutableStateFlow<Map<String, List<SseEvent.QuestionAsked>>>(emptyMap())
    val questions: StateFlow<Map<String, List<SseEvent.QuestionAsked>>> = _questions.asStateFlow()

    override fun handle(event: SseEvent, serverId: String): Boolean {
        return when (event) {
            is SseEvent.QuestionAsked -> { handleQuestionAsked(event); true }
            is SseEvent.QuestionReplied -> { handleQuestionReplied(event); true }
            is SseEvent.QuestionRejected -> { handleQuestionRejected(event); true }
            else -> false
        }
    }

    private fun handleQuestionAsked(event: SseEvent.QuestionAsked) {
        _questions.update { current ->
            val sessionQs = current[event.sessionId]?.toMutableList() ?: mutableListOf()
            sessionQs.add(event)
            current + (event.sessionId to sessionQs)
        }
    }

    private fun handleQuestionReplied(event: SseEvent.QuestionReplied) {
        _questions.update { current ->
            val sessionQs = current[event.sessionId]?.filter { it.id != event.requestId }
            if (sessionQs != null) current + (event.sessionId to sessionQs) else current
        }
    }

    private fun handleQuestionRejected(event: SseEvent.QuestionRejected) {
        _questions.update { current ->
            val sessionQs = current[event.sessionId]?.filter { it.id != event.requestId }
            if (sessionQs != null) current + (event.sessionId to sessionQs) else current
        }
    }

    fun removeQuestion(questionId: String) {
        _questions.update { current ->
            current.mapValues { (_, qs) -> qs.filter { it.id != questionId } }
        }
    }

    fun setQuestions(sessionId: String, qs: List<SseEvent.QuestionAsked>) {
        _questions.update { current ->
            if (qs.isEmpty()) current - sessionId else current + (sessionId to qs)
        }
    }

    fun clearForSession(sessionId: String) {
        _questions.update { it - sessionId }
    }

    fun clearForServer(sessionIds: Set<String>) {
        _questions.update { it - sessionIds }
    }

    fun clearAll() {
        _questions.value = emptyMap()
    }
}
