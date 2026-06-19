package dev.minios.ocremote.ui.screens.workspace

import dev.minios.ocremote.domain.model.FileNode
import dev.minios.ocremote.domain.model.VcsChange

enum class WorkspacePanel { FILE_TREE, GIT_CHANGES }

data class FileTreeNode(
    val node: FileNode,
    val children: List<FileTreeNode>? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

data class WorkspaceUiState(
    val currentPanel: WorkspacePanel = WorkspacePanel.FILE_TREE,
    val directory: String = "",
    val rootNodes: List<FileTreeNode> = emptyList(),
    val rootLoading: Boolean = true,
    val rootError: Int? = null,
    val showIgnored: Boolean = false,
    val gitChanges: List<VcsChange> = emptyList(),
    val gitLoading: Boolean = false,
    val gitError: Int? = null,
    val isNonGit: Boolean = false,
    val gitChangeCount: Int? = null
)

data class DirectoryLoadResult(
    val path: String,
    val nodes: List<FileNode>,
    val error: String?
)
