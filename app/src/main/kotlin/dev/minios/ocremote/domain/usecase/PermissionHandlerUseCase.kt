package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.model.PermissionState
import dev.minios.ocremote.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class PermissionHandlerUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    fun observePermissions(sessionId: String): Flow<List<PermissionState>> =
        chatRepository.getPermissionsFlow(sessionId)

    suspend fun respond(serverId: String, permissionId: String, approved: Boolean, message: String?): Result<Boolean> {
        val reply = if (approved) "allow" else "deny"
        return chatRepository.respondPermission(serverId, permissionId, reply, message)
    }
}
