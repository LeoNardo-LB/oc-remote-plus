package dev.leonardo.ocremoteplus.domain.model

/**
 * User message status for the per-message status indicator.
 * Derived from FSM status + message list position.
 */
enum class UserMsgStatus {
    /** Message is queued behind a pending assistant (shows QUEUED badge) */
    Queued,

    /** Session is busy, message confirmed but assistant hasn't completed reply (shows spinning circle) */
    Waiting,

    /** Assistant has completed reply or session is idle (no indicator) */
    Completed,
}
