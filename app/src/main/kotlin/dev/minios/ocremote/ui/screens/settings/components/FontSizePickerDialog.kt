package dev.minios.ocremote.ui.screens.settings.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R
import dev.minios.ocremote.ui.components.AppDialog
import dev.minios.ocremote.ui.components.AppDialogButtons
import dev.minios.ocremote.ui.components.AppPickerList
import dev.minios.ocremote.ui.components.ButtonStyle

@Composable
internal fun FontSizePickerDialog(
    currentSize: String,
    onSizeSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AppDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.settings_font_size),
        showClose = false,
        showDividers = false,
        scrollable = false,
        maxBodyHeight = 480.dp,
        content = {
            AppPickerList(
                options = listOf(
                    "small" to stringResource(R.string.settings_font_size_small),
                    "medium" to stringResource(R.string.settings_font_size_medium),
                    "large" to stringResource(R.string.settings_font_size_large)
                ),
                selectedKey = currentSize,
                onSelect = onSizeSelected,
            )
        },
        buttons = {
            AppDialogButtons(
                listOf(
                    Triple(stringResource(R.string.cancel), ButtonStyle.Secondary, onDismiss),
                )
            )
        }
    )
}
