package dev.minios.ocremote.ui.screens.settings.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.minios.ocremote.R

@Composable
internal fun ImageCompressionMaxSideDialog(
    currentMaxSide: Int,
    onSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(0, 720, 960, 1080, 1440, 1920, 2560)
    SettingsPickerDialog(
        title = stringResource(R.string.settings_compress_images_max_side),
        options = options.map { it to getImageMaxSideDisplayName(it) },
        selectedKey = currentMaxSide,
        onSelect = onSelected,
        onDismiss = onDismiss
    )
}

@Composable
internal fun ImageCompressionQualityDialog(
    currentQuality: Int,
    onSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(40, 50, 60, 70, 80)
    SettingsPickerDialog(
        title = stringResource(R.string.settings_compress_images_quality),
        options = options.map {
            it to stringResource(R.string.settings_compress_images_quality_value, it)
        },
        selectedKey = currentQuality,
        onSelect = onSelected,
        onDismiss = onDismiss
    )
}
