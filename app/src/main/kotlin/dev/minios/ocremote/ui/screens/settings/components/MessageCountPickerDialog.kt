package dev.minios.ocremote.ui.screens.settings.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.minios.ocremote.R

@Composable
internal fun MessageCountPickerDialog(
    currentCount: Int,
    onCountSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    SettingsPickerDialog(
        title = stringResource(R.string.settings_initial_messages),
        options = listOf(20, 50, 100, 200).map { it to "$it" },
        selectedKey = currentCount,
        onSelect = onCountSelected,
        onDismiss = onDismiss
    )
}
