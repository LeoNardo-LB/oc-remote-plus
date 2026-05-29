package dev.minios.ocremote.ui.screens.settings.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.minios.ocremote.R

@Composable
internal fun FontSizePickerDialog(
    currentSize: String,
    onSizeSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    SettingsPickerDialog(
        title = stringResource(R.string.settings_font_size),
        options = listOf(
            "small" to stringResource(R.string.settings_font_size_small),
            "medium" to stringResource(R.string.settings_font_size_medium),
            "large" to stringResource(R.string.settings_font_size_large)
        ),
        selectedKey = currentSize,
        onSelect = onSizeSelected,
        onDismiss = onDismiss
    )
}
