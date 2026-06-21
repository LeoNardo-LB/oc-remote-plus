package dev.leonardo.ocremotev2.data.mapper

import dev.leonardo.ocremotev2.data.dto.response.FileDiffDto
import dev.leonardo.ocremotev2.data.dto.response.VcsBranchDto
import dev.leonardo.ocremotev2.data.dto.response.VcsChangeDto
import dev.leonardo.ocremotev2.domain.model.VcsBranchInfo
import dev.leonardo.ocremotev2.domain.model.VcsChange
import dev.leonardo.ocremotev2.domain.model.VcsFileDiff
import dev.leonardo.ocremotev2.domain.model.VcsStatus

object VcsMapper {

    fun toDomain(dto: VcsChangeDto): VcsChange = VcsChange(
        file = dto.file,
        additions = dto.additions,
        deletions = dto.deletions,
        status = parseStatus(dto.status)
    )

    fun toDomain(dto: VcsBranchDto): VcsBranchInfo = VcsBranchInfo(
        branch = dto.branch,
        defaultBranch = dto.defaultBranch
    )

    fun toDomain(dto: FileDiffDto): VcsFileDiff = VcsFileDiff(
        file = dto.file ?: "",
        patch = dto.patch,
        additions = dto.additions,
        deletions = dto.deletions,
        status = dto.status?.let { parseStatus(it) }
    )

    internal fun parseStatus(s: String?): VcsStatus = when (s) {
        "added" -> VcsStatus.ADDED
        "deleted" -> VcsStatus.DELETED
        "modified" -> VcsStatus.MODIFIED
        null -> VcsStatus.MODIFIED
        else -> VcsStatus.MODIFIED
    }
}
