package dev.leonardo.ocremotev2.ui.screens.chat.util

import androidx.compose.runtime.compositionLocalOf
import dev.leonardo.ocremotev2.ui.screens.chat.tools.DefaultToolCardResolver
import dev.leonardo.ocremotev2.ui.screens.chat.tools.ToolCardResolver

// ============ Chat Settings via CompositionLocal ============

/** Chat font size setting: "small", "medium", "large". */
val LocalChatFontSize = compositionLocalOf { "medium" }

/** Whether code blocks use word wrap instead of horizontal scroll. */
val LocalCodeWordWrap = compositionLocalOf { true }

/** Whether compact message spacing is enabled. */
val LocalCompactMessages = compositionLocalOf { false }

/** Whether tool cards are collapsed by default. */
val LocalCollapseTools = compositionLocalOf { false }

/** Whether reasoning blocks are expanded by default. */
val LocalExpandReasoning = compositionLocalOf { false }

/** Whether haptic feedback is enabled. */
val LocalHapticFeedbackEnabled = compositionLocalOf { true }

/** Image save request callback available to image preview composables. */
val LocalImageSaveRequest = compositionLocalOf<(ByteArray, String, String?) -> Unit> { { _, _, _ -> } }

/** Persisted expand/collapse state for tool cards, keyed by Part.Tool.id or Part.Patch.id. */
val LocalToolExpandedStates = compositionLocalOf<Map<String, Boolean>> { emptyMap() }

/** Callback to toggle a tool card's expanded state by its part id. */
val LocalOnToggleToolExpanded = compositionLocalOf<(String, Boolean) -> Unit> { { _, _ -> } }

/** Resolver for tool-specific card composables. */
val LocalToolCardResolver = compositionLocalOf<ToolCardResolver> {
    DefaultToolCardResolver()
}
