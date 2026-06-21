package dev.leonardo.ocremotev2.domain.repository

import dev.leonardo.ocremotev2.domain.model.VcsBranchInfo
import dev.leonardo.ocremotev2.domain.model.VcsChange
import dev.leonardo.ocremotev2.domain.model.VcsDiffMode
import dev.leonardo.ocremotev2.domain.model.VcsFileDiff

interface VcsRepository {
    suspend fun getBranch(serverId: String, directory: String): Result<VcsBranchInfo>
    suspend fun getStatus(serverId: String, directory: String): Result<List<VcsChange>>
    suspend fun getDiff(serverId: String, directory: String, mode: VcsDiffMode, context: Int = 3): Result<List<VcsFileDiff>>
}
