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
internal fun ReconnectModePickerDialog(
    currentMode: String,
    onModeSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AppDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.dialog_select_reconnect_mode),
        showClose = false,
        showDividers = false,
        scrollable = false,
        maxBodyHeight = 480.dp,
        content = {
            AppPickerList(
                options = listOf(
                    "aggressive" to stringResource(R.string.settings_reconnect_aggressive),
                    "normal" to stringResource(R.string.settings_reconnect_normal),
                    "conservative" to stringResource(R.string.settings_reconnect_conservative)
                ),
                selectedKey = currentMode,
                onSelect = onModeSelected,
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
