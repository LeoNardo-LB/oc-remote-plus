package dev.leonardo.ocremotev2.fakes

import dev.leonardo.ocremotev2.domain.model.VcsBranchInfo
import dev.leonardo.ocremotev2.domain.model.VcsChange
import dev.leonardo.ocremotev2.domain.model.VcsDiffMode
import dev.leonardo.ocremotev2.domain.model.VcsFileDiff
import dev.leonardo.ocremotev2.domain.repository.VcsRepository
import javax.inject.Singleton

@Singleton
class FakeVcsRepository : VcsRepository {

    var getBranchResult: Result<VcsBranchInfo> = Result.success(VcsBranchInfo(branch = "main", defaultBranch = "main"))
    var getStatusResult: Result<List<VcsChange>> = Result.success(emptyList())
    var getDiffResult: Result<List<VcsFileDiff>> = Result.success(emptyList())

    override suspend fun getBranch(serverId: String, directory: String): Result<VcsBranchInfo> = getBranchResult

    override suspend fun getStatus(serverId: String, directory: String): Result<List<VcsChange>> = getStatusResult

    override suspend fun getDiff(
        serverId: String,
        directory: String,
        mode: VcsDiffMode,
        context: Int
    ): Result<List<VcsFileDiff>> = getDiffResult
}
