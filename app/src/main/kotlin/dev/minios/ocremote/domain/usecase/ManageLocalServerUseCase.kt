package dev.minios.ocremote.domain.usecase

import android.content.Context
import dev.minios.ocremote.domain.model.LocalServerState
import dev.minios.ocremote.domain.repository.ServerRepository
import javax.inject.Inject

/**
 * Use case: manage local server (start/stop/status/setup).
 * Used by Phase 4 HomeViewModel / LocalRuntimeCard.
 */
class ManageLocalServerUseCase @Inject constructor(
    private val serverRepository: ServerRepository
) {
    fun getSetupCommand(): String = serverRepository.getLocalSetupCommand()

    suspend fun setup(context: Context): Result<Unit> = serverRepository.setupLocalServer(context)

    suspend fun start(context: Context): Result<Unit> = serverRepository.startLocalServer(context)

    suspend fun stop(context: Context): Result<Unit> = serverRepository.stopLocalServer(context)

    suspend fun getState(): Result<LocalServerState> = serverRepository.getLocalServerState()
}
