package dev.minios.ocremote.ui.screens.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R
import dev.minios.ocremote.ui.screens.chat.markdown.MarkdownContent
import dev.minios.ocremote.ui.screens.chat.util.LocalHapticFeedbackEnabled
import dev.minios.ocremote.ui.screens.chat.util.halfScreenHeight
import dev.minios.ocremote.ui.screens.chat.util.performHaptic
import dev.minios.ocremote.ui.theme.ShapeTokens
import dev.minios.ocremote.ui.theme.AlphaTokens

@Composable
internal fun ReasoningBlock(text: String, isExpanded: Boolean = false, onToggleExpand: () -> Unit = {}, durationMs: Long? = null, isStreaming: Boolean = false) {
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    val expanded = isExpanded

    val accentColor = MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.MEDIUM)
    val containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = AlphaTokens.MEDIUM)
    val textColor = MaterialTheme.colorScheme.onSurface

    // Pulse animation for the thinking dot (runs only while durationMs == null = still thinking)
    val infiniteTransition = rememberInfiniteTransition(label = "thinkingPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes { durationMillis = 1200; 0.7f at 400; 0.4f at 800 },
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val isComplete = durationMs != null && !isStreaming
    val headerText = when {
        isStreaming && text.isBlank() -> stringResource(R.string.chat_thinking)
        isStreaming -> stringResource(R.string.chat_thinking)
        isComplete && durationMs != null -> stringResource(R.string.chat_thinking_complete, formatReasoningDuration(durationMs))
        else -> stringResource(R.string.chat_status_thinking)
    }

    Surface(
        shape = ShapeTokens.none,
        color = containerColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // Accent left bar
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .fillMaxHeight()
                    .background(accentColor)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { performHaptic(hapticView, hapticOn); onToggleExpand() }
                    .padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Animated pulse dot (shows only while thinking)
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .drawBehind {
                                    drawCircle(
                                        color = accentColor.copy(
                                            alpha = if (isComplete) 0.4f else pulseAlpha
                                        )
                                    )
                                }
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = headerText,
                            style = MaterialTheme.typography.labelMedium,
                            color = textColor.copy(alpha = AlphaTokens.MUTED)
                        )
                    }

                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded)
                            stringResource(R.string.chat_collapse)
                        else
                            stringResource(R.string.chat_expand),
                        modifier = Modifier.size(18.dp),
                        tint = textColor.copy(alpha = AlphaTokens.FAINT)
                    )
                }

                // Streaming placeholder when text is empty
                if (isStreaming && text.isBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = accentColor.copy(alpha = AlphaTokens.MUTED)
                    )
                }

                // Expandable content
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Column {
                        Spacer(modifier = Modifier.height(6.dp))
                        val halfScreenHeight = halfScreenHeight()
                        val reasoningScrollState = rememberScrollState()
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = halfScreenHeight)
                                .verticalScroll(reasoningScrollState)
                        ) {
                        MarkdownContent(
                            markdown = text,
                            textColor = textColor.copy(alpha = AlphaTokens.MUTED),
                            isUser = false,
                            customFontSize = "small"
                        )
                        }
                    }
                }
            }
        }
    }
}

private fun formatReasoningDuration(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> "${"%.1f".format(ms / 1000.0)}s"
    else -> "${"%.1f".format(ms / 60_000.0)}m"
}
