package dev.leonardo.ocremotev2.ui.screens.chat.tools

import dev.leonardo.ocremotev2.domain.model.Part
import dev.leonardo.ocremotev2.ui.screens.chat.ChatMessage
import dev.leonardo.ocremotev2.ui.screens.chat.filterRenderableParts

import androidx.compose.runtime.Immutable

/**
 * Pre-computed rendering data for one display item (one assistant turn bubble).
 *
 * All filtering, grouping, and metadata extraction happen in [computeRenderableTurn],
 * so the Composable only iterates [renderItems] — zero computation during composition.
 *
 * Marked @Immutable so Compose can skip recomposition for items whose RenderableTurn
 * hasn't changed (e.g., during pagination, existing items' data is identical).
 */
@Immutable
data class RenderableTurn(
    val renderItems: List<RenderItem>,
    val isEmpty: Boolean,
    val errorText: String?,
    val agentName: String?,
    val modelId: String?,
    val durationMs: Long?,
    val stepFinishes: List<Part.StepFinish>,
    val taskAgentName: String?,
    val copyText: String?,
)

@Immutable
sealed class RenderItem {
    @Immutable
    data class TurnDivider(val msgId: String) : RenderItem()
    @Immutable
    data class GroupedParts(val group: PartGroup) : RenderItem()
}

/**
 * Compute all rendering data for a display item in a single pass.
 * Called from a `remember` block — runs only when messages change, not during composition.
 */
fun computeRenderableTurn(
    turnMessages: List<ChatMessage>?,
    currentMessage: ChatMessage,
    isTurnLast: Boolean,
    formatError: (dev.leonardo.ocremotev2.domain.model.Message.Assistant.ErrorInfo?) -> String?,
): RenderableTurn {
    val ordered = turnMessages?.reversed() ?: listOf(currentMessage)

    // Single pass: filter + group + dividers
    val renderItems = mutableListOf<RenderItem>()
    for ((msgIndex, msg) in ordered.withIndex()) {
        val msgParts = filterRenderableParts(msg.parts)
        val groups = groupContextParts(msgParts)
        for (group in groups) {
            renderItems.add(RenderItem.GroupedParts(group))
        }
        if (msgIndex < ordered.lastIndex && msgParts.isNotEmpty()) {
            renderItems.add(RenderItem.TurnDivider(msg.message.id))
        }
    }

    // Error text — first error in turn
    val errorText = ordered.firstNotNullOfOrNull { msg ->
        val am = msg.message as? dev.leonardo.ocremotev2.domain.model.Message.Assistant
        formatError(am?.error)
    }

    // Agent name from last message (only on turn-last display item)
    val lastMsg = ordered.lastOrNull()?.message as? dev.leonardo.ocremotev2.domain.model.Message.Assistant
    val agentName = if (isTurnLast) lastMsg?.agent else null
    val modelId = if (isTurnLast) lastMsg?.modelId else null

    // Duration
    val durationMs: Long? = if (isTurnLast && ordered.size > 0) {
        val first = ordered.firstOrNull()?.message
        val last = ordered.lastOrNull()?.message
        if (first is dev.leonardo.ocremotev2.domain.model.Message.Assistant &&
            last is dev.leonardo.ocremotev2.domain.model.Message.Assistant) {
            last.time.completed?.let { end -> end - first.time.created }
        } else null
    } else null

    // Step finishes for token stats
    val stepFinishes = if (isTurnLast) {
        ordered.flatMap { msg -> msg.parts.filterIsInstance<Part.StepFinish>() }
    } else {
        emptyList()
    }

    // Task agent name (for task tool parts)
    val taskAgentName = ordered
        .flatMap { it.parts }
        .filterIsInstance<Part.Agent>()
        .firstOrNull()?.name?.takeIf { it.isNotBlank() }

    // Copy text — all text parts joined
    val copyText = ordered
        .flatMap { it.parts.filterIsInstance<Part.Text>() }
        .map { it.text }
        .joinToString("\n\n")
        .takeIf { it.isNotBlank() }

    return RenderableTurn(
        renderItems = renderItems,
        isEmpty = renderItems.isEmpty() && errorText == null,
        errorText = errorText,
        agentName = agentName,
        modelId = modelId,
        durationMs = durationMs,
        stepFinishes = stepFinishes,
        taskAgentName = taskAgentName,
        copyText = copyText,
    )
}
