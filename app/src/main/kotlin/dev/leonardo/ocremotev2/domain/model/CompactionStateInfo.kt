package dev.leonardo.ocremotev2.domain.model

/**
 * Domain model for compaction state.
 * Counterpart of data.repository.handler.CompactionStateInfo.
 */
data class CompactionStateInfo(
    val isActive: Boolean,
    val reason: String = ""
)
