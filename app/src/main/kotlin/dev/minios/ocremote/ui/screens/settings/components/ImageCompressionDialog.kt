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
internal fun ImageCompressionMaxSideDialog(
    currentMaxSide: Int,
    onSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(0, 720, 960, 1080, 1440, 1920, 2560)
    AppDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.settings_compress_images_max_side),
        showClose = false,
        showDividers = false,
        scrollable = false,
        maxBodyHeight = 480.dp,
        content = {
            AppPickerList(
                options = options.map { it to getImageMaxSideDisplayName(it) },
                selectedKey = currentMaxSide,
                onSelect = onSelected,
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

@Composable
internal fun ImageCompressionQualityDialog(
    currentQuality: Int,
    onSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(40, 50, 60, 70, 80)
    AppDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.settings_compress_images_quality),
        showClose = false,
        showDividers = false,
        scrollable = false,
        maxBodyHeight = 480.dp,
        content = {
            AppPickerList(
                options = options.map {
                    it to stringResource(R.string.settings_compress_images_quality_value, it)
                },
                selectedKey = currentQuality,
                onSelect = onSelected,
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
