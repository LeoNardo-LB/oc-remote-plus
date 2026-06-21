package dev.leonardo.ocremotev2.data.dto.request

import kotlinx.serialization.Serializable

@Serializable
data class QuestionReplyBody(
    val answers: List<List<String>>
)
