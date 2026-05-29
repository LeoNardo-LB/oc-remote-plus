package dev.minios.ocremote.data.dto.request

import dev.minios.ocremote.data.dto.common.ModelSelection
import kotlinx.serialization.Serializable

@Serializable
data class ShellRequest(
    val agent: String,
    val model: ModelSelection? = null,
    val command: String
)
