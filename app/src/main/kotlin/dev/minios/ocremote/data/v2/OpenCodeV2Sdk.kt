package dev.minios.ocremote.data.v2

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

// --- Response types ---

@Serializable
data class MessagesResponse(
    val data: List<SessionMessage>,
    val nextCursor: String? = null,
)

@Serializable
data class PromptRequest(
    val prompt: Prompt,
    val delivery: String = "steer",
)

@Serializable
data class PromptResponse(
    val id: String,
    val sessionID: String,
)

// --- Interface ---

interface OpenCodeV2Sdk {
    fun events(): Flow<SseEventV2>
    suspend fun messages(sessionID: String, limit: Int = 200, cursor: String? = null): MessagesResponse
    suspend fun prompt(sessionID: String, text: String, delivery: String = "steer", files: List<FileAttachment>? = null): PromptResponse
    suspend fun abort(sessionID: String)
}

// --- Implementation ---

class OpenCodeV2SdkImpl(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val connectionManager: SseConnectionManager,
) : OpenCodeV2Sdk {

    override fun events(): Flow<SseEventV2> = connectionManager.connect()

    override suspend fun messages(sessionID: String, limit: Int, cursor: String?): MessagesResponse {
        return httpClient.get("$baseUrl/api/session/$sessionID/message") {
            parameter("limit", limit)
            cursor?.let { parameter("cursor", it) }
        }.body()
    }

    override suspend fun prompt(sessionID: String, text: String, delivery: String, files: List<FileAttachment>?): PromptResponse {
        val promptObj = Prompt(text = text, files = files)
        return httpClient.post("$baseUrl/api/session/$sessionID/prompt") {
            setBody(PromptRequest(prompt = promptObj, delivery = delivery))
        }.body()
    }

    override suspend fun abort(sessionID: String) {
        httpClient.post("$baseUrl/api/session/$sessionID/abort")
    }
}
