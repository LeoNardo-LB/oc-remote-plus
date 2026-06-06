package dev.minios.ocremote.domain.repository

import dev.minios.ocremote.domain.model.LocalServerState

/**
 * Local server management (Termux lifecycle).
 */
interface LocalServerRepository {
    fun getLocalSetupCommand(): String
    suspend fun setupLocalServer(): Result<Unit>
    suspend fun startLocalServer(): Result<Unit>
    suspend fun stopLocalServer(): Result<Unit>
    suspend fun getLocalServerState(): Result<LocalServerState>
}
