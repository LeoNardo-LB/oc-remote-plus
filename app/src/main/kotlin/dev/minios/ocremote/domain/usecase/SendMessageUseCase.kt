package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.data.dto.common.ModelSelection
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.dto.request.PromptPart
import dev.minios.ocremote.data.api.ServerConnection
import javax.inject.Inject

/**
 * Use case: send messages to a session.
 * Temporary shell — delegates to OpenCodeApi. Full impl with tests in Phase 4.
 */
class SendMessageUseCase @Inject constructor(
    private val api: OpenCodeApi
) {
    // TODO: Phase 4 — replace api calls with ChatRepository methods

    suspend fun sendPrompt(
        conn: ServerConnection,
        sessionId: String,
        parts: List<PromptPart>,
        model: ModelSelection?,
        agent: String,
        variant: String?,
        directory: String?
    ) {
        api.promptAsync(
            conn = conn,
            sessionId = sessionId,
            parts = parts,
            model = model,
            agent = agent,
            variant = variant,
            directory = directory
        )
    }
}
