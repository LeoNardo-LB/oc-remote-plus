package dev.leonardo.ocremotev2.domain.repository

import dev.leonardo.ocremotev2.domain.model.Draft

interface DraftRepository {
    fun getDraft(sessionId: String): Draft?
    fun saveDraft(sessionId: String, draft: Draft)
    fun clearDraft(sessionId: String)
    fun getDraftSessionIds(): Set<String>
}
