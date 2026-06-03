package dev.minios.ocremote.ui.screens.chat.tools

import androidx.compose.runtime.Composable
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.ui.screens.chat.tools.cards.ApplyPatchToolCard
import dev.minios.ocremote.ui.screens.chat.tools.cards.BashToolCard
import dev.minios.ocremote.ui.screens.chat.tools.cards.EditToolCard
import dev.minios.ocremote.ui.screens.chat.tools.cards.GlobToolCard
import dev.minios.ocremote.ui.screens.chat.tools.cards.ReadToolCard
import dev.minios.ocremote.ui.screens.chat.tools.cards.SearchToolCard
import dev.minios.ocremote.ui.screens.chat.tools.cards.TaskToolCard
import dev.minios.ocremote.ui.screens.chat.tools.cards.WebFetchToolCard
import dev.minios.ocremote.ui.screens.chat.tools.cards.WebSearchToolCard
import dev.minios.ocremote.ui.screens.chat.tools.cards.WriteToolCard
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default tool card resolver that maps tool names to their card composables.
 * Tool names are matched case-insensitively.
 */
@Singleton
class DefaultToolCardResolver @Inject constructor() : ToolCardResolver {

    private val cardMap: Map<String, (
        tool: Part.Tool,
        isExpanded: Boolean,
        onToggleExpand: () -> Unit,
        onViewSubSession: ((String) -> Unit)?,
        turnAgentName: String?
    ) -> @Composable () -> Unit> = mapOf(
        "bash" to { tool, expanded, toggle, _, _ ->
            { BashToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle) }
        },
        "edit" to { tool, expanded, toggle, _, _ ->
            { EditToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle) }
        },
        "multiedit" to { tool, expanded, toggle, _, _ ->
            { EditToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle) }
        },
        "read" to { tool, expanded, toggle, _, _ ->
            { ReadToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle) }
        },
        "write" to { tool, expanded, toggle, _, _ ->
            { WriteToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle) }
        },
        "glob" to { tool, expanded, toggle, _, _ ->
            { GlobToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle) }
        },
        "grep" to { tool, expanded, toggle, _, _ ->
            { SearchToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle) }
        },
        "search" to { tool, expanded, toggle, _, _ ->
            { SearchToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle) }
        },
        "task" to { tool, expanded, toggle, viewSub, agentName ->
            { TaskToolCard(tool = tool, onViewSubSession = viewSub, turnAgentName = agentName, isExpanded = expanded, onToggleExpand = toggle) }
        },
        "webfetch" to { tool, expanded, toggle, _, _ ->
            { WebFetchToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle) }
        },
        "web_fetch" to { tool, expanded, toggle, _, _ ->
            { WebFetchToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle) }
        },
        "websearch" to { tool, expanded, toggle, _, _ ->
            { WebSearchToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle) }
        },
        "web_search" to { tool, expanded, toggle, _, _ ->
            { WebSearchToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle) }
        },
        "apply_patch" to { tool, expanded, toggle, _, _ ->
            { ApplyPatchToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle) }
        },
    )

    override fun resolve(
        tool: Part.Tool,
        isExpanded: Boolean,
        onToggleExpand: () -> Unit,
        onViewSubSession: ((String) -> Unit)?,
        turnAgentName: String?
    ): (@Composable () -> Unit)? {
        val factory = cardMap[tool.tool.lowercase()] ?: return null
        return factory(tool, isExpanded, onToggleExpand, onViewSubSession, turnAgentName)
    }
}
