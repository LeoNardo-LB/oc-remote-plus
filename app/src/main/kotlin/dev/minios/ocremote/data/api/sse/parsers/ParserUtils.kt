package dev.minios.ocremote.data.api.sse.parsers

import kotlinx.serialization.json.*

internal fun JsonObject.str(key: String, default: String = ""): String =
    this[key]?.jsonPrimitive?.content ?: default
