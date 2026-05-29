package dev.minios.ocremote.ui.screens.settings.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.minios.ocremote.R

@Composable
internal fun ReconnectModePickerDialog(
    currentMode: String,
    onModeSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    SettingsPickerDialog(
        title = stringResource(R.string.dialog_select_reconnect_mode),
        options = listOf(
            "aggressive" to stringResource(R.string.settings_reconnect_aggressive),
            "normal" to stringResource(R.string.settings_reconnect_normal),
            "conservative" to stringResource(R.string.settings_reconnect_conservative)
        ),
        selectedKey = currentMode,
        onSelect = onModeSelected,
        onDismiss = onDismiss
    )
}
