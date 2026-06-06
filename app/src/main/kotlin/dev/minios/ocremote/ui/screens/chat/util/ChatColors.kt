package dev.minios.ocremote.ui.screens.chat.util

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.minios.ocremote.domain.model.AgentInfo
import dev.minios.ocremote.ui.theme.AlphaTokens
import dev.minios.ocremote.ui.theme.LocalAmoledMode

@Composable
internal fun isAmoledTheme(): Boolean = LocalAmoledMode.current

@Composable
internal fun toolOutputContainerColor(): Color {
    return if (isSystemInDarkTheme()) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = AlphaTokens.AMOLED)
    }
}

/**
 * Agent color matching the TUI's opencode theme.
 * Color cycle: secondary, accent, success, warning, primary, error, info
 * (same order as TUI's local.tsx color array).
 */
internal val agentColorCycle = listOf(
    Color(0xFF5C9CF5), // secondary — build (blue)
    Color(0xFF9D7CD8), // accent — plan (purple)
    Color(0xFF7FD88F), // success (green)
    Color(0xFFF5A742), // warning (orange)
    Color(0xFFFAB283), // primary (peach)
    Color(0xFFE06C75), // error (red)
    Color(0xFF56B6C2)  // info (cyan)
)

/** QUEUED badge colors */
internal val QueuedBadgeColor = Color(0xFFFFD700)      // Gold background
internal val QueuedBadgeTextColor = Color(0xFF1A1A1A)  // Dark text on gold

internal fun agentColor(agentName: String, agents: List<AgentInfo> = emptyList()): Color {
    val index = agents.indexOfFirst { it.name == agentName }
    return if (index >= 0) {
        agentColorCycle[index % agentColorCycle.size]
    } else {
        agentColorCycle[0]
    }
}
