package dev.minios.ocremote.ui.screens.settings

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.ViewCompact
import androidx.compose.material.icons.filled.WrapText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R
import dev.minios.ocremote.ui.screens.settings.components.FontSizePickerDialog
import dev.minios.ocremote.ui.screens.settings.components.ImageCompressionMaxSideDialog
import dev.minios.ocremote.ui.screens.settings.components.ImageCompressionQualityDialog
import dev.minios.ocremote.ui.screens.settings.components.LanguagePickerDialog
import dev.minios.ocremote.ui.screens.settings.components.LocalServerLaunchOptionsDialog
import dev.minios.ocremote.ui.screens.settings.components.MessageCountPickerDialog
import dev.minios.ocremote.ui.screens.settings.components.ReconnectModePickerDialog
import dev.minios.ocremote.ui.screens.settings.components.SectionHeader
import dev.minios.ocremote.ui.screens.settings.components.TerminalFontSizeDialog
import dev.minios.ocremote.ui.screens.settings.components.ThemePickerDialog
import dev.minios.ocremote.ui.screens.settings.components.getFontSizeDisplayName
import dev.minios.ocremote.ui.screens.settings.components.getImageMaxSideDisplayName
import dev.minios.ocremote.ui.screens.settings.components.getLanguageDisplayName
import dev.minios.ocremote.ui.screens.settings.components.getReconnectModeDisplayName
import dev.minios.ocremote.ui.screens.settings.components.getThemeDisplayName
import kotlin.math.roundToInt

/**
 * Settings Screen - global app preferences.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
) {
    val currentLanguage by viewModel.appLanguage.collectAsState()
    val currentTheme by viewModel.appTheme.collectAsState()
    val dynamicColor by viewModel.dynamicColor.collectAsState()
    val chatFontSize by viewModel.chatFontSize.collectAsState()
    val notificationsEnabled by viewModel.notificationsEnabled.collectAsState()

    val initialMessageCount by viewModel.initialMessageCount.collectAsState()
    val codeWordWrap by viewModel.codeWordWrap.collectAsState()
    val confirmBeforeSend by viewModel.confirmBeforeSend.collectAsState()
    val amoledDark by viewModel.amoledDark.collectAsState()
    val compactMessages by viewModel.compactMessages.collectAsState()
    val collapseTools by viewModel.collapseTools.collectAsState()
    val expandReasoning by viewModel.expandReasoning.collectAsState()
    val hapticFeedback by viewModel.hapticFeedback.collectAsState()
    val reconnectMode by viewModel.reconnectMode.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    val silentNotifications by viewModel.silentNotifications.collectAsState()
    val compressImageAttachments by viewModel.compressImageAttachments.collectAsState()
    val imageAttachmentMaxLongSide by viewModel.imageAttachmentMaxLongSide.collectAsState()
    val imageAttachmentWebpQuality by viewModel.imageAttachmentWebpQuality.collectAsState()
    val showLocalRuntime by viewModel.showLocalRuntime.collectAsState()
    val terminalFontSize by viewModel.terminalFontSize.collectAsState()
    val localProxyEnabled by viewModel.localProxyEnabled.collectAsState()
    val localProxyUrl by viewModel.localProxyUrl.collectAsState()
    val localProxyNoProxy by viewModel.localProxyNoProxy.collectAsState()
    val localServerAllowLan by viewModel.localServerAllowLan.collectAsState()
    val localServerUsername by viewModel.localServerUsername.collectAsState()
    val localServerPassword by viewModel.localServerPassword.collectAsState()
    val localServerRunInBackground by viewModel.localServerRunInBackground.collectAsState()
    val localServerAutoStart by viewModel.localServerAutoStart.collectAsState()
    val localServerStartupTimeoutSec by viewModel.localServerStartupTimeoutSec.collectAsState()

    var showLanguageDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showFontSizeDialog by remember { mutableStateOf(false) }
    var showMessageCountDialog by remember { mutableStateOf(false) }
    var showReconnectModeDialog by remember { mutableStateOf(false) }
    var showTerminalFontSizeDialog by remember { mutableStateOf(false) }
    var showImageMaxSideDialog by remember { mutableStateOf(false) }
    var showImageQualityDialog by remember { mutableStateOf(false) }
    var showLocalLaunchOptionsDialog by remember { mutableStateOf(false) }

    val isAmoledTheme = MaterialTheme.colorScheme.background == Color.Black &&
        MaterialTheme.colorScheme.surface == Color.Black
    val switchColors = if (isAmoledTheme) {
        SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.primary,
            checkedTrackColor = Color.Black,
            checkedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
            uncheckedTrackColor = Color.Black,
            uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)
        )
    } else {
        SwitchDefaults.colors()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.close)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // ======== General ========
            SectionHeader(stringResource(R.string.settings_section_general))

            // Language
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_language)) },
                supportingContent = { Text(getLanguageDisplayName(currentLanguage)) },
                leadingContent = {
                    Icon(Icons.Default.Language, contentDescription = null)
                },
                modifier = Modifier.clickable { showLanguageDialog = true }
            )

            // Reconnect mode
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_reconnect_mode)) },
                supportingContent = { Text(getReconnectModeDisplayName(reconnectMode)) },
                leadingContent = {
                    Icon(Icons.Default.Sync, contentDescription = null)
                },
                modifier = Modifier.clickable { showReconnectModeDialog = true }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ======== Appearance ========
            SectionHeader(stringResource(R.string.settings_section_appearance))

            // Theme
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_theme)) },
                supportingContent = { Text(getThemeDisplayName(currentTheme)) },
                leadingContent = {
                    Icon(Icons.Default.Palette, contentDescription = null)
                },
                modifier = Modifier.clickable { showThemeDialog = true }
            )

            // Dynamic colors (only on Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_dynamic_color)) },
                    supportingContent = { Text(stringResource(R.string.settings_dynamic_color_desc)) },
                    leadingContent = {
                        Icon(Icons.Default.Palette, contentDescription = null)
                    },
                    trailingContent = {
                        Switch(
                            checked = dynamicColor,
                            onCheckedChange = { viewModel.setDynamicColor(it) },
                            colors = switchColors
                        )
                    },
                    modifier = Modifier.clickable { viewModel.setDynamicColor(!dynamicColor) }
                )
            }

            // AMOLED dark mode
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_amoled_dark)) },
                supportingContent = { Text(stringResource(R.string.settings_amoled_dark_desc)) },
                leadingContent = {
                    Icon(Icons.Default.DarkMode, contentDescription = null)
                },
                trailingContent = {
                    Switch(
                        checked = amoledDark,
                        onCheckedChange = { viewModel.setAmoledDark(it) },
                        colors = switchColors
                    )
                },
                modifier = Modifier.clickable { viewModel.setAmoledDark(!amoledDark) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ======== Chat Display ========
            SectionHeader(stringResource(R.string.settings_section_chat_display))

            // Font size
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_font_size)) },
                supportingContent = { Text(getFontSizeDisplayName(chatFontSize)) },
                leadingContent = {
                    Icon(Icons.Default.FormatSize, contentDescription = null)
                },
                modifier = Modifier.clickable { showFontSizeDialog = true }
            )

            // Compact messages
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_compact_messages)) },
                supportingContent = { Text(stringResource(R.string.settings_compact_messages_desc)) },
                leadingContent = {
                    Icon(Icons.Default.ViewCompact, contentDescription = null)
                },
                trailingContent = {
                    Switch(
                        checked = compactMessages,
                        onCheckedChange = { viewModel.setCompactMessages(it) },
                        colors = switchColors
                    )
                },
                modifier = Modifier.clickable { viewModel.setCompactMessages(!compactMessages) }
            )

            // Code word wrap
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_code_word_wrap)) },
                supportingContent = { Text(stringResource(R.string.settings_code_word_wrap_desc)) },
                leadingContent = {
                    Icon(Icons.Default.WrapText, contentDescription = null)
                },
                trailingContent = {
                    Switch(
                        checked = codeWordWrap,
                        onCheckedChange = { viewModel.setCodeWordWrap(it) },
                        colors = switchColors
                    )
                },
                modifier = Modifier.clickable { viewModel.setCodeWordWrap(!codeWordWrap) }
            )

            // Auto-expand tool results
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_auto_expand_tools)) },
                supportingContent = { Text(stringResource(R.string.settings_auto_expand_tools_desc)) },
                leadingContent = {
                    Icon(Icons.Default.UnfoldMore, contentDescription = null)
                },
                trailingContent = {
                    Switch(
                        checked = collapseTools,
                        onCheckedChange = { viewModel.setCollapseTools(it) },
                        colors = switchColors
                    )
                },
                modifier = Modifier.clickable { viewModel.setCollapseTools(!collapseTools) }
            )

            // Expand reasoning by default
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_expand_reasoning)) },
                supportingContent = { Text(stringResource(R.string.settings_expand_reasoning_desc)) },
                leadingContent = {
                    Icon(Icons.Default.Psychology, contentDescription = null)
                },
                trailingContent = {
                    Switch(
                        checked = expandReasoning,
                        onCheckedChange = { viewModel.setExpandReasoning(it) },
                        colors = switchColors
                    )
                },
                modifier = Modifier.clickable { viewModel.setExpandReasoning(!expandReasoning) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ======== Chat Behavior ========
            SectionHeader(stringResource(R.string.settings_section_chat_behavior))

            // Initial message count
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_initial_messages)) },
                supportingContent = { Text("$initialMessageCount") },
                leadingContent = {
                    Icon(Icons.Default.Storage, contentDescription = null)
                },
                modifier = Modifier.clickable { showMessageCountDialog = true }
            )

            // Confirm before send
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_confirm_send)) },
                supportingContent = { Text(stringResource(R.string.settings_confirm_send_desc)) },
                leadingContent = {
                    Icon(Icons.Default.Send, contentDescription = null)
                },
                trailingContent = {
                    Switch(
                        checked = confirmBeforeSend,
                        onCheckedChange = { viewModel.setConfirmBeforeSend(it) },
                        colors = switchColors
                    )
                },
                modifier = Modifier.clickable { viewModel.setConfirmBeforeSend(!confirmBeforeSend) }
            )

            // Haptic feedback
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_haptic_feedback)) },
                supportingContent = { Text(stringResource(R.string.settings_haptic_feedback_desc)) },
                leadingContent = {
                    Icon(Icons.Default.Vibration, contentDescription = null)
                },
                trailingContent = {
                    Switch(
                        checked = hapticFeedback,
                        onCheckedChange = { viewModel.setHapticFeedback(it) },
                        colors = switchColors
                    )
                },
                modifier = Modifier.clickable { viewModel.setHapticFeedback(!hapticFeedback) }
            )

            // Keep screen on
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_keep_screen_on)) },
                supportingContent = { Text(stringResource(R.string.settings_keep_screen_on_desc)) },
                leadingContent = {
                    Icon(Icons.Default.ScreenLockPortrait, contentDescription = null)
                },
                trailingContent = {
                    Switch(
                        checked = keepScreenOn,
                        onCheckedChange = { viewModel.setKeepScreenOn(it) },
                        colors = switchColors
                    )
                },
                modifier = Modifier.clickable { viewModel.setKeepScreenOn(!keepScreenOn) }
            )

            // Optimize image attachments
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_compress_images)) },
                supportingContent = { Text(stringResource(R.string.settings_compress_images_desc)) },
                leadingContent = {
                    Icon(Icons.Default.PhotoSizeSelectLarge, contentDescription = null)
                },
                trailingContent = {
                    Switch(
                        checked = compressImageAttachments,
                        onCheckedChange = { viewModel.setCompressImageAttachments(it) },
                        colors = switchColors
                    )
                },
                modifier = Modifier.clickable {
                    viewModel.setCompressImageAttachments(!compressImageAttachments)
                }
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_compress_images_max_side)) },
                supportingContent = { Text(getImageMaxSideDisplayName(imageAttachmentMaxLongSide)) },
                leadingContent = {
                    Icon(Icons.Default.PhotoSizeSelectLarge, contentDescription = null)
                },
                modifier = Modifier.clickable(enabled = compressImageAttachments) { showImageMaxSideDialog = true }
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_compress_images_quality)) },
                supportingContent = {
                    Text(stringResource(R.string.settings_compress_images_quality_value, imageAttachmentWebpQuality))
                },
                leadingContent = {
                    Icon(Icons.Default.PhotoSizeSelectLarge, contentDescription = null)
                },
                modifier = Modifier.clickable(enabled = compressImageAttachments) { showImageQualityDialog = true }
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_terminal_font_size)) },
                supportingContent = {
                    Text(stringResource(R.string.settings_terminal_font_size_value, terminalFontSize.roundToInt()))
                },
                leadingContent = {
                    Icon(Icons.Default.Terminal, contentDescription = null)
                },
                modifier = Modifier.clickable { showTerminalFontSizeDialog = true }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ======== Advanced ========
            SectionHeader(stringResource(R.string.settings_section_advanced))

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_local_runtime)) },
                supportingContent = { Text(stringResource(R.string.settings_local_runtime_desc)) },
                leadingContent = {
                    Icon(Icons.Default.Code, contentDescription = null)
                },
                trailingContent = {
                    Switch(
                        checked = showLocalRuntime,
                        onCheckedChange = { viewModel.setShowLocalRuntime(it) },
                        colors = switchColors,
                    )
                },
                modifier = Modifier.clickable { viewModel.setShowLocalRuntime(!showLocalRuntime) },
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.home_local_launch_options)) },
                supportingContent = { Text(stringResource(R.string.home_local_launch_options_desc)) },
                leadingContent = {
                    Icon(Icons.Default.Settings, contentDescription = null)
                },
                modifier = Modifier.clickable { showLocalLaunchOptionsDialog = true },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ======== Notifications ========
            SectionHeader(stringResource(R.string.settings_section_notifications))

            // Notifications
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_notifications)) },
                supportingContent = { Text(stringResource(R.string.settings_notifications_desc)) },
                leadingContent = {
                    Icon(Icons.Default.Notifications, contentDescription = null)
                },
                trailingContent = {
                    Switch(
                        checked = notificationsEnabled,
                        onCheckedChange = { viewModel.setNotificationsEnabled(it) },
                        colors = switchColors
                    )
                },
                modifier = Modifier.clickable { viewModel.setNotificationsEnabled(!notificationsEnabled) }
            )

            // Silent notifications
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_silent_notifications)) },
                supportingContent = { Text(stringResource(R.string.settings_silent_notifications_desc)) },
                leadingContent = {
                    Icon(Icons.Default.NotificationsOff, contentDescription = null)
                },
                trailingContent = {
                    Switch(
                        checked = silentNotifications,
                        onCheckedChange = { viewModel.setSilentNotifications(it) },
                        colors = switchColors
                    )
                },
                modifier = Modifier.clickable { viewModel.setSilentNotifications(!silentNotifications) }
            )

        }

        if (showThemeDialog) {
            ThemePickerDialog(
                currentTheme = currentTheme,
                onThemeSelected = { theme ->
                    viewModel.setTheme(theme)
                    showThemeDialog = false
                },
                onDismiss = { showThemeDialog = false }
            )
        }

        if (showLanguageDialog) {
            LanguagePickerDialog(
                currentLanguage = currentLanguage,
                onLanguageSelected = { languageCode ->
                    viewModel.setLanguage(languageCode)
                    showLanguageDialog = false
                },
                onDismiss = { showLanguageDialog = false }
            )
        }

        if (showFontSizeDialog) {
            FontSizePickerDialog(
                currentSize = chatFontSize,
                onSizeSelected = { size ->
                    viewModel.setChatFontSize(size)
                    showFontSizeDialog = false
                },
                onDismiss = { showFontSizeDialog = false }
            )
        }

        if (showMessageCountDialog) {
            MessageCountPickerDialog(
                currentCount = initialMessageCount,
                onCountSelected = { count ->
                    viewModel.setInitialMessageCount(count)
                    showMessageCountDialog = false
                },
                onDismiss = { showMessageCountDialog = false }
            )
        }

        if (showReconnectModeDialog) {
            ReconnectModePickerDialog(
                currentMode = reconnectMode,
                onModeSelected = { mode ->
                    viewModel.setReconnectMode(mode)
                    showReconnectModeDialog = false
                },
                onDismiss = { showReconnectModeDialog = false }
            )
        }

        if (showTerminalFontSizeDialog) {
            TerminalFontSizeDialog(
                currentSize = terminalFontSize,
                onSizeSelected = { size ->
                    viewModel.setTerminalFontSize(size)
                    showTerminalFontSizeDialog = false
                },
                onDismiss = { showTerminalFontSizeDialog = false }
            )
        }

        if (showImageMaxSideDialog) {
            ImageCompressionMaxSideDialog(
                currentMaxSide = imageAttachmentMaxLongSide,
                onSelected = { px ->
                    viewModel.setImageAttachmentMaxLongSide(px)
                    showImageMaxSideDialog = false
                },
                onDismiss = { showImageMaxSideDialog = false }
            )
        }

        if (showImageQualityDialog) {
            ImageCompressionQualityDialog(
                currentQuality = imageAttachmentWebpQuality,
                onSelected = { quality ->
                    viewModel.setImageAttachmentWebpQuality(quality)
                    showImageQualityDialog = false
                },
                onDismiss = { showImageQualityDialog = false }
            )
        }

        if (showLocalLaunchOptionsDialog) {
            LocalServerLaunchOptionsDialog(
                enabled = localProxyEnabled,
                proxyUrl = localProxyUrl,
                noProxyList = localProxyNoProxy,
                allowLanAccess = localServerAllowLan,
                serverUsername = localServerUsername,
                serverPassword = localServerPassword,
                runInBackground = localServerRunInBackground,
                autoStart = localServerAutoStart,
                startupTimeoutSec = localServerStartupTimeoutSec,
                onDismiss = { showLocalLaunchOptionsDialog = false },
                onSave = { enabled, proxyUrl, noProxyList, allowLanAccess, serverUsername, serverPassword, runInBackground, autoStart, startupTimeoutSec ->
                    viewModel.setLocalProxyEnabled(enabled)
                    viewModel.setLocalProxyUrl(proxyUrl)
                    viewModel.setLocalProxyNoProxy(noProxyList)
                    viewModel.setLocalServerAllowLan(allowLanAccess)
                    viewModel.setLocalServerUsername(serverUsername)
                    viewModel.setLocalServerPassword(serverPassword)
                    viewModel.setLocalServerRunInBackground(runInBackground)
                    viewModel.setLocalServerAutoStart(autoStart && runInBackground)
                    viewModel.setLocalServerStartupTimeoutSec(startupTimeoutSec)
                    showLocalLaunchOptionsDialog = false
                },
            )
        }
    }
}
