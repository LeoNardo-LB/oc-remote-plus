package dev.minios.ocremote.ui.screens.sessions.components

import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.ui.screens.sessions.SessionItem

/**
 * Sealed interface for tree nodes displayed in the session list.
 * Directories are expandable containers; Sessions are leaf items.
 */
sealed interface TreeNode {
    val id: String

    data class Directory(
        override val id: String,           // full path string (used as unique key)
        val path: String,                  // raw file path
        val displayName: String,           // leaf segment for display
        val depth: Int,                    // indentation level (0 = root)
        val childDirectoryCount: Int,      // direct child directories
        val sessionCount: Int,             // sessions in this exact directory
        val totalSessionCount: Int,        // sessions including subdirectories
        val isExpanded: Boolean,
    ) : TreeNode

    data class Session(
        override val id: String,           // sessionId
        val session: SessionItem,          // existing session wrapper
        val depth: Int,                    // indentation level (parent depth + 1)
    ) : TreeNode
}

/**
 * Build a flat list of TreeNodes from sessions and expanded paths.
 *
 * Algorithm:
 * 1. Collect all unique directory paths from sessions
 * 2. Split each path by '/' and generate all ancestor segments
 * 3. Build a tree of Directory nodes, deduplicating by path
 * 4. Assign depth (root = 0)
 * 5. Count sessions per directory (exact match + recursive)
 * 6. Flatten: emit Directory node, if expanded emit children then sessions
 *
 * @param sessions All sessions for this server (already filtered)
 * @param expandedPaths Set of directory paths currently expanded
 * @param homeDir Server home directory for display name simplification
 * @return Flat list of TreeNodes in display order
 */
fun buildTreeNodes(
    sessions: List<Session>,
    expandedPaths: Set<String>,
    homeDir: String?,
    statuses: Map<String, SessionStatus> = emptyMap(),
): List<TreeNode> {
    // 0. Normalize paths: Windows backslashes → forward slashes
    val normalizePath: (String) -> String = { it.replace('\\', '/') }
    val normalizedHomeDir = homeDir?.let { normalizePath(it) }
    val normalizedExpandedPaths = expandedPaths.map { normalizePath(it) }.toMutableSet()

    // 1. Collect all unique directory paths
    val allPaths = sessions.map { normalizePath(it.directory) }.filter { it.isNotBlank() }.toSet()

    // 2. Generate all ancestor segments
    val allSegments = mutableSetOf<String>()
    for (path in allPaths) {
        var current = path.trimEnd('/')
        while (current.isNotEmpty()) {
            allSegments.add(current)
            val lastSlash = current.lastIndexOf('/')
            if (lastSlash <= 0) break
            current = current.substring(0, lastSlash)
        }
    }

    // 3. Build directory info map: path → (displayName, depth, childDirs, sessionCount, totalSessionCount)
    val dirInfo = mutableMapOf<String, DirInfo>()
    for (path in allSegments) {
        val leaf = if (normalizedHomeDir != null && path == normalizedHomeDir.trimEnd('/')) {
            "~"
        } else {
            path.substringAfterLast('/')
        }
        val depth = if (path.startsWith("/")) path.count { it == '/' } - 1 else path.count { it == '/' }
        dirInfo[path] = DirInfo(
            displayName = leaf.ifBlank { path },
            depth = depth,
        )
    }

    // 4. Count sessions per directory (exact match, using normalized paths)
    val exactCount = mutableMapOf<String, Int>()
    for (session in sessions) {
        val dir = normalizePath(session.directory)
        if (dir.isNotBlank()) {
            exactCount[dir] = (exactCount[dir] ?: 0) + 1
        }
    }

    // 5. Count child directories
    val childDirs = mutableMapOf<String, Int>()
    for (path in allSegments) {
        val parent = path.substringBeforeLast('/')
        if (parent in allSegments) {
            childDirs[parent] = (childDirs[parent] ?: 0) + 1
        }
    }

    // 6. Compute total session count (recursive) using bottom-up accumulation
    val sortedPaths = allSegments.sortedBy { it.count { c -> c == '/' } }
    val totalSessionCount = mutableMapOf<String, Int>()
    for (path in sortedPaths) {
        totalSessionCount[path] = (exactCount[path] ?: 0)
    }
    // Propagate counts upward
    for (path in sortedPaths.sortedByDescending { it.count { c -> c == '/' } }) {
        val parent = path.substringBeforeLast('/')
        if (parent in allSegments && parent != path) {
            totalSessionCount[parent] = (totalSessionCount[parent] ?: 0) + (totalSessionCount[path] ?: 0)
        }
    }

    // 7. Flatten tree respecting visit order
    val result = mutableListOf<TreeNode>()
    val rootPaths = allSegments.filter { path ->
        val parent = path.substringBeforeLast('/')
        // A path is root if its parent is not in allSegments,
        // OR if its parent equals itself (e.g., drive root "D:" on Windows)
        parent !in allSegments || parent == path
    }.sortedBy { it }

    // 7.5 Collapse chains of single-child, zero-session directories
    collapseSingleChildChains(allSegments, dirInfo, childDirs, exactCount, normalizedHomeDir, normalizedExpandedPaths)

    // Recompute root paths after collapsing (some nodes may have become roots)
    val collapsedRootPaths = allSegments.filter { path ->
        val parent = path.substringBeforeLast('/')
        parent !in allSegments || parent == path
    }.sortedBy { it }

    for (rootPath in collapsedRootPaths) {
        flattenSubtree(rootPath, allSegments, sessions, normalizedExpandedPaths, exactCount, totalSessionCount, childDirs, dirInfo, normalizedHomeDir, statuses, result)
    }

    // Handle sessions with empty/blank/root directory (no tree node)
    val noDirSessions = sessions.filter { normalizePath(it.directory).isBlank() || normalizePath(it.directory) == "/" }

    for (session in noDirSessions) {
        result.add(TreeNode.Session(
            id = session.id,
            session = SessionItem(session = session, status = statuses[session.id] ?: SessionStatus.Idle),
            depth = 0,
        ))
    }

    return result
}

private data class DirInfo(
    val displayName: String,
    val depth: Int,
)

private fun flattenSubtree(
    path: String,
    allSegments: Set<String>,
    sessions: List<Session>,
    expandedPaths: Set<String>,
    exactCount: Map<String, Int>,
    totalSessionCount: Map<String, Int>,
    childDirs: Map<String, Int>,
    dirInfo: Map<String, DirInfo>,
    homeDir: String?,
    statuses: Map<String, SessionStatus>,
    result: MutableList<TreeNode>,
) {
    val info = dirInfo[path] ?: return

    // Emit directory node
    result.add(TreeNode.Directory(
        id = path,
        path = path,
        displayName = info.displayName,
        depth = info.depth,
        childDirectoryCount = childDirs[path] ?: 0,
        sessionCount = exactCount[path] ?: 0,
        totalSessionCount = totalSessionCount[path] ?: 0,
        isExpanded = path in expandedPaths,
    ))

    // If expanded, emit child directories (sorted) then sessions
    if (path in expandedPaths) {
        val childPaths = allSegments
            .filter { child ->
                child != path &&
                child.substringBeforeLast('/') == path &&
                child.startsWith("$path/")
            }
            .sortedBy { it }

        for (childPath in childPaths) {
            flattenSubtree(childPath, allSegments, sessions, expandedPaths, exactCount, totalSessionCount, childDirs, dirInfo, homeDir, statuses, result)
        }

        // Sessions in this directory (exact match, sorted by updated desc)
        val dirSessions = sessions
            .filter { it.directory.replace('\\', '/') == path }
            .sortedByDescending { it.time.updated }

        for (session in dirSessions) {
            result.add(TreeNode.Session(
                id = session.id,
                session = SessionItem(session = session, status = statuses[session.id] ?: SessionStatus.Idle),
                depth = info.depth + 1,
            ))
        }
    }
}

/**
 * Collapse chains of directories that have exactly 1 child directory and 0 sessions.
 * Iteratively merges parent into child until no more collapsing is possible.
 * Updates depths, display names, and expanded paths in-place.
 */
private fun collapseSingleChildChains(
    allSegments: MutableSet<String>,
    dirInfo: MutableMap<String, DirInfo>,
    childDirs: MutableMap<String, Int>,
    exactCount: Map<String, Int>,
    normalizedHomeDir: String?,
    normalizedExpandedPaths: MutableSet<String>,
) {
    val originalDepths = allSegments.associateWith { dirInfo[it]?.depth ?: 0 }

    var changed = true
    while (changed) {
        changed = false
        val toRemove = mutableListOf<String>()

        for (path in allSegments.toList().sortedBy { dirInfo[it]?.depth ?: 0 }) {
            if ((exactCount[path] ?: 0) != 0) continue

            val children = allSegments.filter { child ->
                child.substringBeforeLast('/') == path && child.startsWith("$path/")
            }
            if (children.size == 1) {
                val child = children[0]

                // Promote child to parent's depth
                dirInfo[child] = dirInfo[child]!!.copy(
                    depth = dirInfo[path]!!.depth,
                )

                // Propagate expanded state
                if (path in normalizedExpandedPaths) {
                    normalizedExpandedPaths.remove(path)
                    normalizedExpandedPaths.add(child)
                }

                toRemove.add(path)
                changed = true
            } else if (children.isEmpty()) {
                // Remove empty leaf directories (no children, no sessions)
                toRemove.add(path)
                changed = true
            }
        }

        for (path in toRemove) {
            allSegments.remove(path)
            childDirs.remove(path)
        }
    }

    // Update display names for nodes whose depth changed (i.e., were collapsed)
    for (path in allSegments) {
        val originalDepth = originalDepths[path] ?: continue
        val currentDepth = dirInfo[path]?.depth ?: continue
        if (currentDepth != originalDepth) {
            dirInfo[path] = dirInfo[path]!!.copy(
                displayName = buildCollapsedDisplayName(path, normalizedHomeDir),
            )
        }
    }
}

/**
 * Build display name for a collapsed directory node.
 * Drops the first path segment (drive letter on Windows, empty segment on Unix root).
 * Applies homeDir substitution if applicable.
 */
private fun buildCollapsedDisplayName(path: String, homeDir: String?): String {
    val trimmedHome = homeDir?.trimEnd('/')
    return when {
        trimmedHome != null && path.startsWith("$trimmedHome/") ->
            "~/${path.removePrefix("$trimmedHome/")}"
        trimmedHome != null && path == trimmedHome -> "~"
        path.startsWith('/') -> path.substring(1)
        path.length >= 2 && path[1] == ':' -> {
            val slashIdx = path.indexOf('/')
            if (slashIdx >= 0) path.substring(slashIdx + 1) else path
        }
        else -> path
    }.ifBlank { "/" }
}
