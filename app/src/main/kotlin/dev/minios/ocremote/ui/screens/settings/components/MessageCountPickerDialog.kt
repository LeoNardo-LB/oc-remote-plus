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
internal fun MessageCountPickerDialog(
    currentCount: Int,
    onCountSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AppDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.settings_initial_messages),
        showClose = false,
        showDividers = false,
        scrollable = false,
        maxBodyHeight = 480.dp,
        content = {
            AppPickerList(
                options = listOf(20, 50, 100, 200).map { it to "$it" },
                selectedKey = currentCount,
                onSelect = onCountSelected,
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
