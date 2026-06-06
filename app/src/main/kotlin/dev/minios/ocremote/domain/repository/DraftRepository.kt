package dev.minios.ocremote.domain.repository

import dev.minios.ocremote.domain.model.Draft

interface DraftRepository {
    fun getDraft(sessionId: String): Draft?
    fun saveDraft(sessionId: String, draft: Draft)
    fun clearDraft(sessionId: String)
}
