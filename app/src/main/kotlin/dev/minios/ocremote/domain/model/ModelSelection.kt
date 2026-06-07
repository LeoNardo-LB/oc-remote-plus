package dev.minios.ocremote.domain.model

/**
 * Domain model for selecting a provider/model pair.
 * Counterpart of data.dto.common.ModelSelection.
 */
data class ModelSelection(
    val providerId: String,
    val modelId: String
)
