package dev.leonardo.ocremotev2.ui.screens.sessions.components

import dev.leonardo.ocremotev2.domain.model.Session
import dev.leonardo.ocremotev2.domain.model.SessionStatus
import dev.leonardo.ocremotev2.ui.screens.sessions.SessionItem

/**
 * Sealed interface for flat session list nodes.
 * Two levels: Directory (expandable group) and Session (leaf item).
 */
sealed interface TreeNode {
    val id: String

    data class Directory(
        override val id: String,
        val path: String,
        val displayName: String,
        val sessionCount: Int,
        val activeSessionCount: Int,
        val isExpanded: Boolean,
    ) : TreeNode

    data class Session(
        override val id: String,
        val session: SessionItem,
    ) : TreeNode
}

/**
 * Build a flat 2-level node list from sessions.
 *
 * When baseDirectory is set:
 *   - Sessions are grouped by their first path segment relative to baseDirectory
 *   - Sessions directly in baseDirectory appear at the top (ungrouped)
 *   - Each group is an expandable Directory node
 *
 * When baseDirectory is null:
 *   - All sessions are shown flat, no grouping
 *
 * @param sessions Filtered sessions (already scoped to server, not archived, etc.)
 * @param expandedDirs Set of directory IDs currently expanded
 * @param baseDirectory The selected base directory path (normalized, e.g. "D:/Develop"), or null
 * @param statuses Session status map
 */
fun buildTreeNodes(
    sessions: List<Session>,
    expandedDirs: Set<String>,
    baseDirectory: String?,
    statuses: Map<String, SessionStatus> = emptyMap(),
    draftSessionIds: Set<String> = emptySet(),
): List<TreeNode> {
    val result = mutableListOf<TreeNode>()
    val dirSessions = sortedMapOf<String, MutableList<Session>>()
    val rootSessions = mutableListOf<Session>()

    val normalizedBase = baseDirectory?.replace('\\', '/')?.trimEnd('/')

    for (session in sessions) {
        val dir = session.directory.replace('\\', '/').trimEnd('/')
        if (dir.isEmpty()) {
            rootSessions.add(session)
            continue
        }

        if (normalizedBase != null) {
            if (!dir.startsWith(normalizedBase)) continue
            val relative = dir.removePrefix(normalizedBase).removePrefix("/")
            if (relative.isEmpty()) {
                rootSessions.add(session)
            } else {
                val firstSegment = relative.substringBefore('/')
                dirSessions.getOrPut(firstSegment) { mutableListOf() }.add(session)
            }
        } else {
            // No base directory: group by full directory path
            dirSessions.getOrPut(dir) { mutableListOf() }.add(session)
        }
    }

    // Directory groups FIRST — only expand when explicitly toggled
    for ((dirKey, dirSessionList) in dirSessions) {
        val fullPath = if (normalizedBase != null) "$normalizedBase/$dirKey" else dirKey
        val isExpanded = fullPath in expandedDirs
        val activeCount = dirSessionList.count { session ->
            val status = statuses[session.id]
            status is SessionStatus.Busy
        }
        result.add(TreeNode.Directory(
            id = dirKey,
            path = fullPath,
            displayName = fullPath,
            sessionCount = dirSessionList.size,
            activeSessionCount = activeCount,
            isExpanded = isExpanded,
        ))
        if (isExpanded) {
            for (session in dirSessionList.sortedByDescending { it.time.updated }) {
                result.add(TreeNode.Session(
                    id = session.id,
                    session = SessionItem(session = session, status = statuses[session.id] ?: SessionStatus.Idle, hasDraft = session.id in draftSessionIds),
                ))
            }
        }
    }

    // Root sessions last (directly in base directory, ungrouped)
    for (session in rootSessions.sortedByDescending { it.time.updated }) {
        result.add(TreeNode.Session(
            id = session.id,
            session = SessionItem(session = session, status = statuses[session.id] ?: SessionStatus.Idle, hasDraft = session.id in draftSessionIds),
        ))
    }

    return result
}
