package dev.minios.ocremote.ui.screens.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.minios.ocremote.R
import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.ui.components.ProviderIcon
import dev.minios.ocremote.ui.screens.chat.ChatMessage
import dev.minios.ocremote.ui.screens.chat.filterRenderableParts
import dev.minios.ocremote.ui.screens.chat.util.LocalCompactMessages
import dev.minios.ocremote.ui.screens.chat.util.LocalHapticFeedbackEnabled
import dev.minios.ocremote.ui.screens.chat.util.formatAssistantErrorMessage
import dev.minios.ocremote.ui.screens.chat.util.formatDuration
import dev.minios.ocremote.ui.screens.chat.util.isAmoledTheme
import dev.minios.ocremote.ui.screens.chat.util.performHaptic

/**
 * Renders a SINGLE assistant message with its parts interleaved.
 * Unlike the old AssistantTurnBubble, this handles exactly one ChatMessage,
 * so streaming text updates only recompose THIS card, not all sibling messages.
 */
@Composable
internal fun AssistantMessageCard(
    chatMessage: ChatMessage,
    isContinuation: Boolean,
    isTurnLast: Boolean = false,
    turnMessages: List<ChatMessage>? = null,
    onViewSubSession: ((String) -> Unit)? = null,
    onCopyText: (() -> Unit)? = null,
) {
    val isAmoled = isAmoledTheme()
    val textColor = if (isAmoled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface

    val assistantMsg = chatMessage.message as? Message.Assistant
    val errorText = formatAssistantErrorMessage(assistantMsg?.error)
    val renderableParts = filterRenderableParts(chatMessage.parts)

    if (renderableParts.isEmpty() && errorText == null) return

    val compact = LocalCompactMessages.current
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (isContinuation) 0.dp else 0.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp)
        ) {
                // "Response" header — only on the first of a consecutive sequence
                if (!isContinuation) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            if (assistantMsg?.providerId != null) {
                                ProviderIcon(
                                    providerId = assistantMsg.providerId,
                                    size = 12.dp,
                                    tint = textColor.copy(alpha = 0.4f)
                                )
                            }
                            Text(
                                text = stringResource(R.string.chat_response),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    letterSpacing = 0.8.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                color = textColor.copy(alpha = 0.4f)
                            )
                            // Local timestamp from message creation time
                            assistantMsg?.time?.created?.let { createdMs ->
                                val timeText = remember(createdMs) {
                                    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                        .format(java.util.Date(createdMs))
                                }
                                Text(
                                    text = timeText,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp
                                    ),
                                    color = textColor.copy(alpha = 0.3f)
                                )
                            }
                        }
                        if (onCopyText != null) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.chat_copy),
                                modifier = Modifier
                                    .size(15.dp)
                                    .clickable { performHaptic(hapticView, hapticOn); onCopyText() },
                                tint = textColor.copy(alpha = 0.3f)
                            )
                        }
                    }
                }

                // Render parts in original order
                for (part in renderableParts) {
                    key(part.id) {
                        PartContent(
                            part = part,
                            textColor = textColor,
                            isUser = false,
                            onViewSubSession = onViewSubSession,
                            turnAgentName = if (part is Part.Tool && part.tool == "task") {
                                val agentParts = chatMessage.parts.filterIsInstance<Part.Agent>()
                                agentParts.firstOrNull()?.name?.takeIf { it.isNotBlank() }
                            } else null
                        )
                    }
                }

                // Token/cost/duration footer — only on the last message of a turn
                val stepFinishes = if (isTurnLast && turnMessages != null) {
                    turnMessages.flatMap { msg ->
                        msg.parts.filterIsInstance<Part.StepFinish>()
                    }
                } else {
                    emptyList()
                }

                if (stepFinishes.isNotEmpty()) {
                    val totalInput = stepFinishes.sumOf { it.tokens?.input ?: 0 }
                    val totalOutput = stepFinishes.sumOf { it.tokens?.output ?: 0 }
                    val totalCost = stepFinishes.sumOf { it.cost ?: 0.0 }
                    val hasTokenStats = totalInput > 0 || totalOutput > 0 || totalCost > 0.0

                    val durationMs = if (isTurnLast && turnMessages != null) {
                        val first = turnMessages.firstOrNull()?.message
                        val last = turnMessages.lastOrNull()?.message
                        if (first is Message.Assistant && last is Message.Assistant) {
                            last.time.completed?.let { end -> end - first.time.created }
                        } else null
                    } else null

                    val modelId = if (isTurnLast && turnMessages != null) {
                        (turnMessages.lastOrNull()?.message as? Message.Assistant)?.modelId
                    } else null

                    val hasFooter = hasTokenStats || durationMs != null || !modelId.isNullOrBlank()

                    if (hasFooter) {
                        Spacer(modifier = Modifier.height(if (compact) 4.dp else 8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (assistantMsg?.providerId != null) {
                                ProviderIcon(
                                    providerId = assistantMsg.providerId,
                                    size = 10.dp,
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                                )
                            }
                            if (!modelId.isNullOrBlank()) {
                                Text(
                                    text = modelId,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                            }
                            if (durationMs != null && durationMs > 0) {
                                Text(
                                    text = formatDuration(durationMs),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                                )
                            }
                            if (totalInput > 0 || totalOutput > 0) {
                                Text(
                                    text = stringResource(R.string.chat_tokens_format, totalInput, totalOutput),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                                )
                            }
                            if (totalCost > 0.0 && totalCost.isFinite()) {
                                Text(
                                    text = stringResource(R.string.chat_cost_format, String.format("%.4f", totalCost)),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                                )
                            }
                            // Footer copy button
                            if (onCopyText != null) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = stringResource(R.string.chat_copy),
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable {
                                            performHaptic(hapticView, hapticOn)
                                            onCopyText()
                                        },
                                    tint = textColor.copy(alpha = 0.3f)
                                )
                            }
                        }
                    }
                }

                // Error display
                if (errorText != null) {
                    Surface(
                        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = if (isAmoled) 0.75f else 0.35f)),
                        tonalElevation = 0.dp,
                    ) {
                        ErrorPayloadContent(
                            text = errorText,
                            textStyle = MaterialTheme.typography.bodySmall,
                            textColor = textColor,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                    }
                }
            }
    }
}
