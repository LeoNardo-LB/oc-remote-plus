package dev.minios.ocremote.ui.screens.sessions.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.minios.ocremote.R
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.ui.components.indicators.PulsingDotsIndicator
import dev.minios.ocremote.ui.screens.sessions.SessionItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun SessionRow(
    item: SessionItem,
    projectName: String? = null,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val isAmoled = isAmoledTheme()
    val dateFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    val addColor = Color(0xFF4CAF50)
    val delColor = Color(0xFFE53935)

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { dismissValue ->
            when (dismissValue) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onRename()
                    false // don't settle — snap back
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onDelete()
                    false
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
        positionalThreshold = { it * 0.3f }
    )

    val cardContent: @Composable () -> Unit = {
        val containerColor = if (isSelected) {
            if (isAmoled) {
                Color.Black
            } else {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            }
        } else {
            if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceVariant
        }

        val cardColors = CardDefaults.cardColors(containerColor = containerColor)

        val cardBorder = when {
            isSelected -> BorderStroke(
                1.5.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = if (isAmoled) 0.75f else 0.5f)
            )
            isAmoled -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
            else -> null
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
            colors = cardColors,
            border = cardBorder
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AnimatedVisibility(
                    visible = isSelectionMode,
                    enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
                    exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onClick() },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                }
                // Content column
                Column(modifier = Modifier.weight(1f)) {
                    // Project name label
                    if (!projectName.isNullOrBlank()) {
                        Text(
                            text = projectName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                    }

                    // Title
                    Text(
                        text = item.session.title ?: stringResource(R.string.session_untitled),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    // Date + status + diff summary row
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = dateFormat.format(Date(item.session.time.updated)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )

                        // Status indicator
                        when (item.status) {
                            is SessionStatus.Busy -> {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    PulsingDotsIndicator(
                                        dotSize = 4.dp,
                                        dotSpacing = 2.dp,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                    Text(
                                        text = stringResource(R.string.sessions_working),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                            }
                            is SessionStatus.Retry -> {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.error)
                                    )
                                    Text(
                                        text = stringResource(R.string.sessions_retrying),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            else -> { /* Idle - no label */ }
                        }

                        // Diff summary (+N/-N)
                        val summary = item.session.summary
                        if (summary != null && (summary.additions > 0 || summary.deletions > 0)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                if (summary.additions > 0) {
                                    Text(
                                        text = stringResource(R.string.session_changes_additions, summary.additions),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = addColor
                                        )
                                    )
                                }
                                if (summary.deletions > 0) {
                                    Text(
                                        text = stringResource(R.string.session_changes_deletions, summary.deletions),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = delColor
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val direction = dismissState.dismissDirection

            // Rename background (swipe right)
            val renameColor = MaterialTheme.colorScheme.primaryContainer
            // Delete background (swipe left)
            val deleteColor = MaterialTheme.colorScheme.errorContainer

            val bgColor = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> renameColor
                SwipeToDismissBoxValue.EndToStart -> deleteColor
                else -> Color.Transparent
            }
            val iconTint = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.onPrimaryContainer
                SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.onErrorContainer
                else -> Color.Transparent
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CardDefaults.shape)
                    .background(bgColor)
                    .padding(horizontal = 20.dp),
                contentAlignment = when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                    else -> Alignment.CenterEnd
                }
            ) {
                when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = stringResource(R.string.session_rename),
                                tint = iconTint,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                stringResource(R.string.session_rename),
                                style = MaterialTheme.typography.labelMedium,
                                color = iconTint
                            )
                        }
                    }
                    SwipeToDismissBoxValue.EndToStart -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.delete),
                                style = MaterialTheme.typography.labelMedium,
                                color = iconTint
                            )
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.delete),
                                tint = iconTint,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    else -> {}
                }
            }
        },
        enableDismissFromStartToEnd = !isSelectionMode,
        enableDismissFromEndToStart = !isSelectionMode
    ) {
        cardContent()
    }
}
