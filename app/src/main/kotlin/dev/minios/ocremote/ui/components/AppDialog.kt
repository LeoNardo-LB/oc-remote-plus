package dev.minios.ocremote.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.minios.ocremote.ui.screens.sessions.components.isAmoledTheme
import dev.minios.ocremote.ui.theme.AlphaTokens
import dev.minios.ocremote.ui.theme.ShapeTokens

enum class ButtonStyle {
    Primary,
    Secondary,
    Danger,
}

@Composable
fun AppDialog(
    onDismiss: () -> Unit,
    title: String,
    isAmoled: Boolean = isAmoledTheme(),
    showClose: Boolean = true,
    showDividers: Boolean = true,
    maxBodyHeight: Dp? = null,
    scrollable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
    buttons: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        AmoledSurface(
            isAmoledDark = isAmoled,
            shape = ShapeTokens.large,
            modifier = Modifier.fillMaxWidth(0.92f),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (showClose) {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = null,
                            )
                        }
                    }
                }

                if (showDividers) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT),
                    )
                }

                // Content
                if (scrollable) {
                    val heightMod = if (maxBodyHeight != null) Modifier.heightIn(max = maxBodyHeight) else Modifier
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .then(heightMod)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        content = content,
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .then(if (maxBodyHeight != null) Modifier.heightIn(max = maxBodyHeight) else Modifier)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        content = content,
                    )
                }

                if (showDividers) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT),
                    )
                }

                // Buttons
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    content = buttons,
                )
            }
        }
    }
}

@Composable
fun ColumnScope.AppDialogButtons(
    buttons: List<Triple<String, ButtonStyle, () -> Unit>>,
) {
    if (buttons.size <= 2) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            buttons.forEach { (text, style, onClick) ->
                DialogButton(
                    text = text,
                    style = style,
                    onClick = onClick,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            buttons.forEach { (text, style, onClick) ->
                DialogButton(
                    text = text,
                    style = style,
                    onClick = onClick,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun DialogButton(
    text: String,
    style: ButtonStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (style) {
        ButtonStyle.Primary -> {
            FilledTonalButton(onClick = onClick, modifier = modifier) { Text(text) }
        }
        ButtonStyle.Secondary -> {
            OutlinedButton(onClick = onClick, modifier = modifier) { Text(text) }
        }
        ButtonStyle.Danger -> {
            OutlinedButton(
                onClick = onClick,
                modifier = modifier,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(text) }
        }
    }
}
