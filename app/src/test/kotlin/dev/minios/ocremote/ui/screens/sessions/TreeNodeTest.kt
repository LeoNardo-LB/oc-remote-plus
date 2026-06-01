package dev.minios.ocremote.ui.screens.sessions

import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.ui.screens.sessions.components.TreeNode
import dev.minios.ocremote.ui.screens.sessions.components.buildTreeNodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeNodeTest {

    private fun makeSession(id: String, directory: String) = Session(
        id = id,
        directory = directory,
        time = Session.Time(created = 1000L, updated = 1000L),
    )

    @Test
    fun `empty sessions returns empty list`() {
        val result = buildTreeNodes(emptyList(), emptySet(), null)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `single directory with sessions - collapsed`() {
        val sessions = listOf(
            makeSession("s1", "/home/user/project-a"),
            makeSession("s2", "/home/user/project-a"),
        )

        val result = buildTreeNodes(sessions, emptySet(), "/home/user")

        // Should have directory nodes for /home, /home/user, /home/user/project-a
        // None expanded, so only root-level
        assertTrue(result.isNotEmpty())
        val dirs = result.filterIsInstance<TreeNode.Directory>()
        assertTrue(dirs.isNotEmpty())

        // No session nodes because nothing is expanded
        val sessNodes = result.filterIsInstance<TreeNode.Session>()
        assertTrue(sessNodes.isEmpty())
    }

    @Test
    fun `single directory with sessions - expanded`() {
        val sessions = listOf(
            makeSession("s1", "/home/user/project-a"),
            makeSession("s2", "/home/user/project-a"),
        )

        // Expand all paths
        val expanded = setOf("/home", "/home/user", "/home/user/project-a")
        val result = buildTreeNodes(sessions, expanded, null)

        val sessNodes = result.filterIsInstance<TreeNode.Session>()
        assertEquals(2, sessNodes.size)
        assertEquals("s1", sessNodes[0].id)
        assertEquals("s2", sessNodes[1].id)
    }

    @Test
    fun `depth reflects hierarchy level`() {
        val sessions = listOf(
            makeSession("s1", "/a/b/c"),
        )

        val expanded = setOf("/a", "/a/b", "/a/b/c")
        val result = buildTreeNodes(sessions, expanded, null)

        // Single-child chains are collapsed: /a → /a/b → /a/b/c → single node
        val dirs = result.filterIsInstance<TreeNode.Directory>()
        assertEquals(1, dirs.size)

        val dir = dirs.first()
        assertEquals("/a/b/c", dir.path)
        assertEquals(0, dir.depth) // collapsed to root depth
        assertEquals("a/b/c", dir.displayName)
    }

    @Test
    fun `session depth is parent directory depth + 1`() {
        val sessions = listOf(
            makeSession("s1", "/a/b"),
        )

        val expanded = setOf("/a", "/a/b")
        val result = buildTreeNodes(sessions, expanded, null)

        val sessNode = result.filterIsInstance<TreeNode.Session>().first()
        assertEquals(1, sessNode.depth) // collapsed parent "/a/b" is depth 0, session is depth 1
    }

    @Test
    fun `session count tracks exact and total`() {
        val sessions = listOf(
            makeSession("s1", "/a"),
            makeSession("s2", "/a/b"),
        )

        val result = buildTreeNodes(sessions, emptySet(), null)

        val dirA = result.filterIsInstance<TreeNode.Directory>().first { it.path == "/a" }
        assertEquals(1, dirA.sessionCount)     // only s1 is directly in /a
        assertEquals(2, dirA.totalSessionCount) // s1 + s2 are under /a recursively
    }

    @Test
    fun `childDirectoryCount counts direct children`() {
        val sessions = listOf(
            makeSession("s1", "/a/b"),
            makeSession("s2", "/a/c"),
            makeSession("s3", "/a/b/d"),
        )

        val result = buildTreeNodes(sessions, emptySet(), null)

        val dirA = result.filterIsInstance<TreeNode.Directory>().first { it.path == "/a" }
        assertEquals(2, dirA.childDirectoryCount) // /a/b and /a/c
    }

    @Test
    fun `sessions with empty directory appear at root depth 0`() {
        val sessions = listOf(
            makeSession("s1", ""),
        )

        val result = buildTreeNodes(sessions, emptySet(), null)

        val sessNode = result.filterIsInstance<TreeNode.Session>().firstOrNull()
        assertEquals("s1", sessNode?.id)
        assertEquals(0, sessNode?.depth)
    }

    @Test
    fun `partial expand shows only expanded children`() {
        val sessions = listOf(
            makeSession("s1", "/a"),
            makeSession("s2", "/a/b"),
        )

        // Only expand /a, not /a/b
        val expanded = setOf("/a")
        val result = buildTreeNodes(sessions, expanded, null)

        val sessNodes = result.filterIsInstance<TreeNode.Session>()
        assertEquals(1, sessNodes.size) // only s1 (in /a), s2 not visible because /a/b not expanded
        assertEquals("s1", sessNodes[0].id)
    }
}
