package dev.minios.ocremote.ui.screens.chat.tools.cards

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R
import dev.minios.ocremote.ui.components.indicators.PulsingDotsIndicator
import dev.minios.ocremote.ui.screens.chat.util.LocalHapticFeedbackEnabled
import dev.minios.ocremote.ui.screens.chat.util.isAmoledTheme
import dev.minios.ocremote.ui.screens.chat.util.performHaptic

/**
 * Shared scaffold for all tool cards.
 * Encapsulates the common Surface + title row + expand pattern.
 *
 * @param icon Leading icon (16dp)
 * @param iconTint Tint for the leading icon
 * @param title Title text (used when [titleContent] is null)
 * @param copyText Text copied to clipboard via the built-in copy button. Blank hides the button.
 * @param isExpanded Current expand state
 * @param isRunning Whether the tool is currently running (shows pulsing dots)
 * @param hasContent Whether there is content to show (controls right-side visibility + animation)
 * @param isAmoled AMOLED theme flag
 * @param onToggleExpand Callback when title row is clicked (default expand toggle)
 * @param onClick Optional override for the title row click. If null, uses onToggleExpand.
 * @param rightSideExtras Extra composables on the right side of the title row (e.g. DiffChangesInline)
 * @param titleContent Optional custom title content. If null, a simple icon + text row is used.
 * @param expandedContent Content shown when expanded
 */
@Composable
internal fun ToolCardScaffold(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    copyText: String,
    isExpanded: Boolean,
    isRunning: Boolean,
    hasContent: Boolean,
    isAmoled: Boolean,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    rightSideExtras: @Composable (RowScope.() -> Unit)? = null,
    titleContent: (@Composable RowScope.() -> Unit)? = null,
    expandedContent: @Composable () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    val expanded = isExpanded

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
        tonalElevation = if (isAmoled) 0.dp else 1.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(4.dp)) {
            // Title row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        performHaptic(hapticView, hapticOn)
                        (onClick ?: onToggleExpand)()
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: icon + title
                if (titleContent != null) {
                    titleContent(this)
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = iconTint
                        )
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // Right: extras + (running indicator OR copy + expand)
                if (isRunning) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        rightSideExtras?.invoke(this)
                        PulsingDotsIndicator(
                            dotSize = 5.dp,
                            dotSpacing = 3.dp,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                } else if (hasContent) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        rightSideExtras?.invoke(this)
                        if (copyText.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(copyText))
                                    Toast.makeText(context, context.getString(R.string.chat_copied_clipboard), Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(22.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = context.getString(R.string.chat_copy),
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                )
                            }
                        }
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            }

            // Expanded content
            AnimatedVisibility(
                visible = expanded && hasContent,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                expandedContent()
            }
        }
    }
}
