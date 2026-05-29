package dev.minios.ocremote.data.dto.request

import kotlinx.serialization.Serializable

@Serializable
data class QuestionReplyBody(
    val answers: List<List<String>>
)
