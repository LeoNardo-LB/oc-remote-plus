package dev.leonardo.ocremotev2.fakes

import dev.leonardo.ocremotev2.domain.model.Draft
import dev.leonardo.ocremotev2.domain.repository.DraftRepository
import javax.inject.Singleton

@Singleton
class FakeDraftRepository : DraftRepository {

    private val drafts = mutableMapOf<String, Draft>()

    override fun getDraft(sessionId: String): Draft? = drafts[sessionId]

    override fun saveDraft(sessionId: String, draft: Draft) {
        drafts[sessionId] = draft
    }

    override fun clearDraft(sessionId: String) {
        drafts.remove(sessionId)
    }

    override fun getDraftSessionIds(): Set<String> = drafts.keys.toSet()
}
