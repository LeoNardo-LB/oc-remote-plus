package dev.minios.ocremote.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.ui.theme.AlphaTokens
import dev.minios.ocremote.ui.theme.LocalAmoledMode

/**
 * Role of a button inside a dialog.
 *
 * - [Primary]:   Main action (confirm, save, create). Renders as FilledTonalButton.
 * - [Secondary]: Cancel / dismiss. Renders as TextButton with default color.
 * - [Danger]:    Destructive action (delete, revert). Renders as TextButton with error color.
 */
enum class DialogButtonRole {
    Primary,
    Secondary,
    Danger,
}

/**
 * Unified dialog button row.
 *
 * Layout rules:
 * - 1 button: single Row, right-aligned
 * - 2 buttons: Row, horizontal, right-aligned
 * - 3+ buttons: Column, vertical, full-width
 *
 * @param buttons List of (label, role, onClick) triples.
 */
@Composable
fun DialogButtons(
    buttons: List<Triple<String, DialogButtonRole, () -> Unit>>,
) {
    if (buttons.size <= 2) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            buttons.forEach { (text, role, onClick) ->
                DialogActionButton(
                    text = text,
                    role = role,
                    onClick = onClick,
                )
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            buttons.forEach { (text, role, onClick) ->
                DialogActionButton(
                    text = text,
                    role = role,
                    onClick = onClick,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun DialogActionButton(
    text: String,
    role: DialogButtonRole,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (role) {
        DialogButtonRole.Primary -> {
            FilledTonalButton(onClick = onClick, modifier = modifier) {
                Text(text)
            }
        }
        DialogButtonRole.Secondary -> {
            TextButton(onClick = onClick, modifier = modifier) {
                Text(text)
            }
        }
        DialogButtonRole.Danger -> {
            TextButton(
                onClick = onClick,
                modifier = modifier,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
            ) {
                Text(text)
            }
        }
    }
}

/**
 * [ButtonColors] for [FilledTonalButton] that adapts to AMOLED dark mode.
 *
 * AMOLED: Black container + primary content + primary border.
 * Normal: Default tonal button colors, no border.
 *
 * Use for full-width card action buttons (Connect, Start, etc.) to eliminate
 * per-button AMOLED boilerplate.
 */
@Composable
fun amoledTonalButtonColors(): ButtonColors {
    val isAmoled = LocalAmoledMode.current
    return if (isAmoled) {
        ButtonDefaults.filledTonalButtonColors(
            containerColor = Color.Black,
            contentColor = MaterialTheme.colorScheme.primary,
        )
    } else {
        ButtonDefaults.filledTonalButtonColors()
    }
}

/**
 * Border for a tonal button in AMOLED mode, or `null` in normal mode.
 * Matches [amoledTonalButtonColors] visual style.
 */
@Composable
fun amoledTonalButtonBorder(): BorderStroke? {
    val isAmoled = LocalAmoledMode.current
    return if (isAmoled) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.HIGH))
    } else {
        null
    }
}
