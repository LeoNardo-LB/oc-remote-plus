package dev.minios.ocremote.data.api.sse.parsers

import kotlinx.serialization.json.*

internal fun JsonObject.str(key: String, default: String = ""): String {
    val element = this[key] ?: return default
    return when {
        element === JsonNull -> default
        element is JsonPrimitive -> element.content
        element is JsonObject -> element["message"]?.jsonPrimitive?.content ?: element.toString()
        element is JsonArray -> element.toString()
        else -> default
    }
}
