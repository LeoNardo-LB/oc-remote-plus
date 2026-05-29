package dev.minios.ocremote.ui.screens.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R
import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.ui.screens.chat.ChatMessage
import dev.minios.ocremote.ui.screens.chat.isBubbleRenderablePart
import dev.minios.ocremote.ui.screens.chat.dialog.ImageThumbnailRow
import dev.minios.ocremote.ui.screens.chat.util.LocalCompactMessages
import dev.minios.ocremote.ui.screens.chat.util.LocalHapticFeedbackEnabled
import dev.minios.ocremote.ui.screens.chat.util.QueuedBadgeColor
import dev.minios.ocremote.ui.screens.chat.util.QueuedBadgeTextColor
import dev.minios.ocremote.ui.screens.chat.util.isAmoledTheme
import dev.minios.ocremote.ui.screens.chat.util.performHaptic
import dev.minios.ocremote.ui.screens.chat.util.resolveUserCommandLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatMessageBubble(
    chatMessage: ChatMessage,
    isQueued: Boolean = false,
    onViewSubSession: ((String) -> Unit)? = null,
    onRevert: (() -> Unit)? = null,
    onCopyText: (() -> Unit)? = null
) {
    val isAmoled = isAmoledTheme()
    val backgroundColor = if (isAmoled) {
        Color.Black
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val textColor = if (isAmoled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    val bubbleBorder = if (isAmoled) {
        BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        )
    } else {
        null
    }
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current

    // Filter visible parts for user messages
    val visibleParts = chatMessage.parts.filter { part ->
        when (part) {
            is Part.Text -> part.synthetic != true && part.ignored != true && part.text.isNotBlank()
            else -> true
        }
    }

    val userMessage = chatMessage.message as? Message.User
    val userFallbackText = userMessage?.summary?.body?.takeIf { it.isNotBlank() }
        ?: userMessage?.summary?.title?.takeIf { it.isNotBlank() }
    val userCommandLabel = resolveUserCommandLabel(chatMessage.parts)

    val contentParts = visibleParts

    val hasRenderableUserPart = contentParts.any(::isBubbleRenderablePart)
    if (!hasRenderableUserPart && userFallbackText == null && userCommandLabel == null) {
        return
    }

        val bubbleContent: @Composable () -> Unit = {
        Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 4.dp,
                bottomStart = 18.dp,
                bottomEnd = 18.dp
            ),
            color = backgroundColor,
            border = bubbleBorder,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            val compact = LocalCompactMessages.current
            Column(
                    modifier = Modifier.padding(
                        horizontal = if (compact) 10.dp else 16.dp,
                        vertical = if (compact) 8.dp else 14.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 10.dp)
                ) {
                    // QUEUED badge for user messages
                    if (isQueued) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = QueuedBadgeColor,
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.chat_queued),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = QueuedBadgeTextColor
                                    ),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    // Content parts (text, reasoning, patches, etc.)
                    // Group image file parts into a compact thumbnail row
                    val imageFiles = contentParts.filterIsInstance<Part.File>()
                        .filter { it.mime.startsWith("image/") && !it.url.isNullOrBlank() }
                    val otherParts = contentParts.filter { part ->
                        !(part is Part.File && part.mime.startsWith("image/") && !part.url.isNullOrBlank())
                    }
                    val renderableOtherParts = otherParts.filter(::isBubbleRenderablePart)

                    // Render image thumbnails as a horizontal row
                    if (imageFiles.isNotEmpty()) {
                        ImageThumbnailRow(imageFiles = imageFiles)
                    }

                    // Render remaining parts
                    for (part in renderableOtherParts) {
                        key(part.id) {
                            PartContent(
                                part = part,
                                textColor = textColor,
                                isUser = true,
                                onViewSubSession = onViewSubSession
                            )
                        }
                    }

                    if (imageFiles.isEmpty() && renderableOtherParts.isEmpty() && userCommandLabel != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.RateReview,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = textColor.copy(alpha = 0.7f)
                            )
                            Text(
                                text = userCommandLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = textColor.copy(alpha = 0.85f)
                            )
                        }
                    }

                    // If text parts are absent but server provided a summary, render it.
                    if (visibleParts.isEmpty() && userFallbackText != null) {
                        Text(
                            text = userFallbackText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor.copy(alpha = 0.5f)
                        )
                    }
                }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End
    ) {
        if (onRevert != null) {
            // Swipe-to-revert for user messages with confirmation dialog
            var showRevertConfirmation by remember { mutableStateOf(false) }
            val hapticEnabled = LocalHapticFeedbackEnabled.current
            val bubbleView = LocalView.current

            val dismissState = rememberSwipeToDismissBoxState(
                confirmValueChange = { value ->
                    if (value != SwipeToDismissBoxValue.Settled) {
                        if (hapticEnabled) {
                            @Suppress("DEPRECATION")
                            bubbleView.performHapticFeedback(
                                android.view.HapticFeedbackConstants.LONG_PRESS,
                                android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
                                        android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                            )
                        }
                        showRevertConfirmation = true
                    }
                    false // don't actually dismiss; wait for dialog confirmation
                }
            )

            if (showRevertConfirmation) {
                AlertDialog(
                    onDismissRequest = { showRevertConfirmation = false },
                    title = { Text(stringResource(R.string.chat_revert_title)) },
                    text = { Text(stringResource(R.string.chat_revert_message)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showRevertConfirmation = false
                                onRevert()
                            }
                        ) {
                            Text(stringResource(R.string.chat_revert), color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showRevertConfirmation = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                )
            }

            SwipeToDismissBox(
                state = dismissState,
                backgroundContent = {
                    val direction = dismissState.dismissDirection
                    val bgColor = MaterialTheme.colorScheme.errorContainer
                    val iconAlignment = if (direction == SwipeToDismissBoxValue.StartToEnd) {
                        Alignment.CenterStart
                    } else {
                        Alignment.CenterEnd
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(
                                topStart = 18.dp,
                                topEnd = 4.dp,
                                bottomStart = 18.dp,
                                bottomEnd = 18.dp
                            ))
                            .background(bgColor)
                            .padding(horizontal = 20.dp),
                        contentAlignment = iconAlignment
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Undo,
                                contentDescription = stringResource(R.string.chat_revert),
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = stringResource(R.string.chat_revert),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                },
                enableDismissFromStartToEnd = true,
                enableDismissFromEndToStart = true
            ) {
                bubbleContent()
            }
        } else {
            // Provide pointer-input isolation boundary to prevent Markdown library's
            // pointerInput handlers (link handling, code background) from leaking
            // into LazyColumn's pointer scope and intercepting clicks on sibling
            // tool cards. In main sessions, SwipeToDismissBox provides this isolation.
            Box(modifier = Modifier.pointerInput(Unit) {
                // Intentionally empty — only creates a pointer-input scope boundary
            }) {
                bubbleContent()
            }
        }
    }
}
