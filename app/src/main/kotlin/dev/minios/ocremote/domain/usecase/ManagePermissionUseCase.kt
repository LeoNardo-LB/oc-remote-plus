package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.model.PermissionState
import dev.minios.ocremote.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case: manage permission requests (observe + reply).
 * Used by Phase 2 ChatViewModel.
 */
class ManagePermissionUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    fun getPermissionsFlow(sessionId: String): Flow<List<PermissionState>> =
        chatRepository.getPermissionsFlow(sessionId)

    suspend fun replyPermission(permissionId: String, reply: String): Result<Boolean> =
        chatRepository.replyPermission(permissionId, reply)
}
