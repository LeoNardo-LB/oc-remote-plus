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
internal fun ThemePickerDialog(
    currentTheme: String,
    onThemeSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AppDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.dialog_select_theme),
        showClose = false,
        showDividers = false,
        scrollable = false,
        maxBodyHeight = 480.dp,
        content = {
            AppPickerList(
                options = listOf(
                    "system" to stringResource(R.string.settings_theme_system),
                    "light" to stringResource(R.string.settings_theme_light),
                    "dark" to stringResource(R.string.settings_theme_dark)
                ),
                selectedKey = currentTheme,
                onSelect = onThemeSelected,
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
