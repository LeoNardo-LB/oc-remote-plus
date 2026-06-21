package dev.leonardo.ocremotev2.data.dto.request

import dev.leonardo.ocremotev2.data.dto.common.ModelSelection
import kotlinx.serialization.Serializable

@Serializable
data class ShellRequest(
    val agent: String,
    val model: ModelSelection? = null,
    val command: String
)
