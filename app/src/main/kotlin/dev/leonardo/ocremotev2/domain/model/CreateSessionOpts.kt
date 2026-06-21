package dev.leonardo.ocremotev2.domain.model

data class CreateSessionOpts(
    val title: String? = null,
    val parentId: String? = null,
    val directory: String? = null
)
