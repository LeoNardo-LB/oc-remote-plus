package dev.minios.ocremote.data.v2

import kotlinx.serialization.Serializable

@Serializable
data class TimeInfo(
    val created: Long = 0L,
    val completed: Long? = null,
)

@Serializable
data class SnapshotInfo(
    val start: String? = null,
    val end: String? = null,
)

@Serializable
data class TokenUsage(
    val input: Long = 0L,
    val output: Long = 0L,
    val reasoning: Long = 0L,
    val cache: CacheUsage = CacheUsage(),
)

@Serializable
data class CacheUsage(val read: Long = 0, val write: Long = 0)

@Serializable
data class ModelRef(
    val providerID: String = "",
    val modelID: String = "",
)

@Serializable
data class FileAttachment(
    val name: String = "",
    val path: String = "",
    val type: String? = null,
)

@Serializable
data class Prompt(
    val text: String = "",
    val files: List<FileAttachment>? = null,
    val agents: List<String>? = null,
    val references: List<String>? = null,
)

@Serializable
data class TodoInfo(
    val id: String = "",
    val text: String = "",
    val completed: Boolean = false,
)
