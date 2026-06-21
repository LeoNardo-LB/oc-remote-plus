package dev.leonardo.ocremotev2.ui.screens.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.leonardo.ocremotev2.R
import dev.leonardo.ocremotev2.ui.components.amoledDialogParams
import dev.leonardo.ocremotev2.ui.components.AppPickerList


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FontSizePickerDialog(
    currentSize: String,
    onSizeSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val params = amoledDialogParams()

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            color = params.containerColor,
            tonalElevation = params.tonalElevation,
            border = params.border,
            shape = params.shape,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.settings_font_size),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))
                AppPickerList(
                    options = listOf(
                        "small" to stringResource(R.string.settings_font_size_small),
                        "medium" to stringResource(R.string.settings_font_size_medium),
                        "large" to stringResource(R.string.settings_font_size_large)
                    ),
                    selectedKey = currentSize,
                    onSelect = onSizeSelected,
                )
            }
        }
    }
}
