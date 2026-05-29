package dev.minios.ocremote.data.mapper

import dev.minios.ocremote.data.dto.response.PermissionRequest
import dev.minios.ocremote.domain.model.SseEvent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Maps between API DTO (PermissionRequest) and Domain (SseEvent.PermissionAsked).
 *
 * Key differences:
 * - API: always is List<String>;  Domain: always is Boolean
 * - API: metadata is Map<String, JsonElement>;  Domain: metadata is Map<String, String>
 */
object PermissionMapper {

    /** API DTO → Domain */
    fun toDomain(dto: PermissionRequest): SseEvent.PermissionAsked {
        val alwaysBoolean = dto.always.isNotEmpty()
        val metadataStrings = dto.metadata?.mapValues { (_, v) ->
            v.jsonPrimitive.contentOrNull ?: v.toString()
        }
        return SseEvent.PermissionAsked(
            id = dto.id,
            sessionId = dto.sessionId,
            permission = dto.permission,
            patterns = dto.patterns,
            metadata = metadataStrings,
            always = alwaysBoolean,
            tool = dto.tool
        )
    }

    /** Domain → API DTO */
    fun toDto(domain: SseEvent.PermissionAsked): PermissionRequest {
        val metadataElements = domain.metadata?.mapValues { (_, v) ->
            JsonPrimitive(v) as JsonElement
        }
        val alwaysList = if (domain.always) listOf("*") else emptyList()
        return PermissionRequest(
            id = domain.id,
            sessionId = domain.sessionId,
            permission = domain.permission,
            patterns = domain.patterns,
            metadata = metadataElements,
            always = alwaysList,
            tool = domain.tool
        )
    }
}
