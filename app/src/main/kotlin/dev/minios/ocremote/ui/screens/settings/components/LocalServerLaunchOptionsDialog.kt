package dev.minios.ocremote.ui.screens.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R
import dev.minios.ocremote.data.repository.LocalServerManager
import dev.minios.ocremote.ui.theme.AlphaTokens
import dev.minios.ocremote.ui.theme.LocalAmoledMode
import dev.minios.ocremote.ui.theme.ShapeTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalServerLaunchOptionsDialog(
    enabled: Boolean,
    proxyUrl: String,
    noProxyList: String,
    allowLanAccess: Boolean,
    serverUsername: String,
    serverPassword: String,
    runInBackground: Boolean,
    autoStart: Boolean,
    startupTimeoutSec: Int,
    onDismiss: () -> Unit,
    onSave: (
        enabled: Boolean,
        proxyUrl: String,
        noProxyList: String,
        allowLanAccess: Boolean,
        serverUsername: String,
        serverPassword: String,
        runInBackground: Boolean,
        autoStart: Boolean,
        startupTimeoutSec: Int,
    ) -> Unit,
) {
    val isAmoled = LocalAmoledMode.current
    var localEnabled by remember(enabled) { mutableStateOf(enabled) }
    var localProxyUrl by remember(proxyUrl) { mutableStateOf(proxyUrl) }
    var localNoProxyList by remember(noProxyList) { mutableStateOf(noProxyList) }
    var localAllowLanAccess by remember(allowLanAccess) { mutableStateOf(allowLanAccess) }
    var localServerUsername by remember(serverUsername) { mutableStateOf(serverUsername) }
    var localServerPassword by remember(serverPassword) { mutableStateOf(serverPassword) }
    var localRunInBackground by remember(runInBackground) { mutableStateOf(runInBackground) }
    var localAutoStart by remember(autoStart) { mutableStateOf(autoStart) }
    var localStartupTimeoutSec by remember(startupTimeoutSec) { mutableIntStateOf(startupTimeoutSec) }
    var maskProxyUrl by remember { mutableStateOf(true) }
    var maskServerPassword by remember { mutableStateOf(true) }
    var timeoutExpanded by remember { mutableStateOf(false) }
    val timeoutOptions = listOf(15, 30, 45, 60, 90, 120)
    val trimmedProxyUrl = localProxyUrl.trim()
    val trimmedNoProxy = localNoProxyList.trim()
    val trimmedServerPassword = localServerPassword.trim()
    val canSave = !localEnabled || trimmedProxyUrl.isNotBlank()

    val switchColors = if (isAmoled) {
        SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.primary,
            checkedTrackColor = Color.Black,
            checkedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.HIGH),
            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
            uncheckedTrackColor = Color.Black,
            uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.HIGH),
        )
    } else {
        SwitchDefaults.colors()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = amoledDialogModifier(),
        shape = ShapeTokens.extraLarge,
        title = {
            Text(
                text = stringResource(R.string.home_local_launch_options),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.home_local_network_section), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

                ListItem(
                    headlineContent = { Text(stringResource(R.string.home_local_allow_lan_access)) },
                    supportingContent = { Text(stringResource(R.string.home_local_allow_lan_access_desc)) },
                    trailingContent = {
                        Switch(
                            checked = localAllowLanAccess,
                            onCheckedChange = { localAllowLanAccess = it },
                            colors = switchColors,
                        )
                    },
                )

                Text(stringResource(R.string.home_local_security_section), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

                OutlinedTextField(
                    value = localServerUsername,
                    onValueChange = { localServerUsername = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.home_local_server_username_label)) },
                    placeholder = { Text(stringResource(R.string.home_local_server_username_placeholder)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                )

                OutlinedTextField(
                    value = localServerPassword,
                    onValueChange = { localServerPassword = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.home_local_server_password_label)) },
                    placeholder = { Text(stringResource(R.string.home_local_server_password_placeholder)) },
                    trailingIcon = {
                        IconButton(onClick = { maskServerPassword = !maskServerPassword }) {
                            Icon(
                                imageVector = if (maskServerPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = null,
                            )
                        }
                    },
                    visualTransformation = if (maskServerPassword) FullStringMaskTransformation else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )

                if (localAllowLanAccess && trimmedServerPassword.isBlank()) {
                    Text(
                        text = stringResource(R.string.home_local_lan_password_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Text(stringResource(R.string.home_local_proxy_section), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

                ListItem(
                    headlineContent = { Text(stringResource(R.string.home_local_proxy_enable)) },
                    supportingContent = { Text(stringResource(R.string.home_local_proxy_url_label)) },
                    trailingContent = {
                        Switch(
                            checked = localEnabled,
                            onCheckedChange = { localEnabled = it },
                            colors = switchColors,
                        )
                    },
                )

                if (localEnabled) {
                    OutlinedTextField(
                        value = localProxyUrl,
                        onValueChange = { localProxyUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.home_local_proxy_url_label)) },
                        placeholder = { Text("http://127.0.0.1:8080") },
                        trailingIcon = {
                            IconButton(onClick = { maskProxyUrl = !maskProxyUrl }) {
                                Icon(
                                    imageVector = if (maskProxyUrl) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = null,
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        isError = trimmedProxyUrl.isBlank(),
                        visualTransformation = if (maskProxyUrl) FullStringMaskTransformation else VisualTransformation.None,
                    )

                    OutlinedTextField(
                        value = localNoProxyList,
                        onValueChange = { localNoProxyList = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        label = { Text(stringResource(R.string.home_local_proxy_no_proxy_label)) },
                        placeholder = { Text(LocalServerManager.DEFAULT_NO_PROXY_LIST) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    )
                }

                Text(
                    text = stringResource(R.string.home_local_proxy_no_proxy_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(stringResource(R.string.home_local_autostart_section), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

                ListItem(
                    headlineContent = { Text(stringResource(R.string.home_local_run_background_label)) },
                    supportingContent = { Text(stringResource(R.string.home_local_run_background_desc)) },
                    trailingContent = {
                        Switch(
                            checked = localRunInBackground,
                            onCheckedChange = {
                                localRunInBackground = it
                                if (!it) {
                                    localAutoStart = false
                                }
                            },
                            colors = switchColors,
                        )
                    },
                )

                ListItem(
                    headlineContent = { Text(stringResource(R.string.home_local_auto_start_label)) },
                    supportingContent = {
                        Text(
                            if (localRunInBackground) {
                                stringResource(R.string.home_local_auto_start_desc)
                            } else {
                                stringResource(R.string.home_local_auto_start_requires_background)
                            }
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = localAutoStart,
                            onCheckedChange = { localAutoStart = it },
                            enabled = localRunInBackground,
                            colors = switchColors,
                        )
                    },
                )

                ExposedDropdownMenuBox(
                    expanded = timeoutExpanded,
                    onExpandedChange = { timeoutExpanded = !timeoutExpanded },
                ) {
                    OutlinedTextField(
                        value = stringResource(R.string.home_local_startup_timeout_value, localStartupTimeoutSec),
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        label = { Text(stringResource(R.string.home_local_startup_timeout_label)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = timeoutExpanded) },
                    )
                    ExposedDropdownMenu(expanded = timeoutExpanded, onDismissRequest = { timeoutExpanded = false }) {
                        timeoutOptions.forEach { value ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.home_local_startup_timeout_value, value)) },
                                onClick = {
                                    localStartupTimeoutSec = value
                                    timeoutExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        localEnabled,
                        trimmedProxyUrl,
                        trimmedNoProxy,
                        localAllowLanAccess,
                        localServerUsername.trim(),
                        trimmedServerPassword,
                        localRunInBackground,
                        localAutoStart && localRunInBackground,
                        localStartupTimeoutSec,
                    )
                },
                enabled = canSave,
            ) {
                Text(stringResource(R.string.server_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        containerColor = amoledDialogContainerColor(),
    )
}

private object FullStringMaskTransformation : VisualTransformation {
    override fun filter(text: androidx.compose.ui.text.AnnotatedString): androidx.compose.ui.text.input.TransformedText {
        val raw = text.text
        if (raw.isEmpty()) {
            return androidx.compose.ui.text.input.TransformedText(text, androidx.compose.ui.text.input.OffsetMapping.Identity)
        }
        val masked = "\u2022".repeat(raw.length)
        return androidx.compose.ui.text.input.TransformedText(
            androidx.compose.ui.text.AnnotatedString(masked),
            androidx.compose.ui.text.input.OffsetMapping.Identity,
        )
    }
}
