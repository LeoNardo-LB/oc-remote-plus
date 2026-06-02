package dev.minios.ocremote.ui.screens.sessions.components

import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.ui.screens.sessions.SessionItem

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
): List<TreeNode> {
    if (baseDirectory == null) {
        // No base directory: show all sessions flat
        return sessions.map { session ->
            TreeNode.Session(
                id = session.id,
                session = SessionItem(session = session, status = statuses[session.id] ?: SessionStatus.Idle),
            )
        }
    }

    val normalizedBase = baseDirectory.replace('\\', '/').trimEnd('/')
    val result = mutableListOf<TreeNode>()
    val dirSessions = sortedMapOf<String, MutableList<Session>>()
    val rootSessions = mutableListOf<Session>()

    for (session in sessions) {
        val dir = session.directory.replace('\\', '/').trimEnd('/')
        val relative = if (dir.startsWith(normalizedBase)) {
            dir.removePrefix(normalizedBase).removePrefix("/")
        } else {
            continue // skip sessions outside base directory
        }

        if (relative.isEmpty()) {
            rootSessions.add(session)
        } else {
            val firstSegment = relative.substringBefore('/')
            dirSessions.getOrPut(firstSegment) { mutableListOf() }.add(session)
        }
    }

    // Root sessions first (ungrouped)
    for (session in rootSessions.sortedByDescending { it.time.updated }) {
        result.add(TreeNode.Session(
            id = session.id,
            session = SessionItem(session = session, status = statuses[session.id] ?: SessionStatus.Idle),
        ))
    }

    // Directory groups — expand all by default when no explicit expanded state
    val expandAll = expandedDirs.isEmpty()
    for ((dirName, dirSessionList) in dirSessions) {
        val fullPath = "$normalizedBase/$dirName"
        val isExpanded = expandAll || fullPath in expandedDirs
        result.add(TreeNode.Directory(
            id = dirName,
            path = fullPath,
            displayName = dirName,
            sessionCount = dirSessionList.size,
            isExpanded = isExpanded,
        ))
        if (isExpanded) {
            for (session in dirSessionList.sortedByDescending { it.time.updated }) {
                result.add(TreeNode.Session(
                    id = session.id,
                    session = SessionItem(session = session, status = statuses[session.id] ?: SessionStatus.Idle),
                ))
            }
        }
    }

    return result
}
