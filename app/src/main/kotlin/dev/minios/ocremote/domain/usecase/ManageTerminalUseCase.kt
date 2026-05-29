package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.ServerConnection
import javax.inject.Inject

/**
 * Use case: manage terminal operations.
 * Temporary shell — delegates to OpenCodeApi. Full impl with tests in Phase 4.
 */
class ManageTerminalUseCase @Inject constructor(
    private val api: OpenCodeApi
) {
    // TODO: Phase 4 — replace api calls with TerminalRepository methods

    suspend fun executeCommand(
        conn: ServerConnection,
        sessionId: String,
        command: String,
        arguments: String,
        directory: String?
    ): Boolean =
        api.executeCommand(conn, sessionId, command, arguments, directory)

    suspend fun runShellCommand(
        conn: ServerConnection,
        sessionId: String,
        command: String,
        agent: String,
        model: dev.minios.ocremote.data.dto.common.ModelSelection?,
        directory: String?
    ): Boolean =
        api.runShellCommand(conn, sessionId, command, agent, model, directory)
}
