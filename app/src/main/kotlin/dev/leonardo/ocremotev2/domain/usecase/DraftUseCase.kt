package dev.leonardo.ocremotev2.domain.usecase

import dev.leonardo.ocremotev2.domain.model.Draft
import dev.leonardo.ocremotev2.domain.repository.DraftRepository
import javax.inject.Inject

/**
 * Use case: manage message drafts (text + attachments + file mentions).
 * Temporary shell — delegates to DraftRepository. Full impl with tests in Phase 4.
 */
class DraftUseCase @Inject constructor(
    private val draftRepository: DraftRepository
) {
    // TODO: Phase 4 — replace draftRepository calls with DraftRepository interface

    fun getDraft(sessionId: String): Draft? = draftRepository.getDraft(sessionId)

    fun saveDraft(sessionId: String, draft: Draft) = draftRepository.saveDraft(sessionId, draft)

    fun clearDraft(sessionId: String) = draftRepository.clearDraft(sessionId)
}
