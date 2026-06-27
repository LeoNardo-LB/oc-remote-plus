package dev.leonardo.ocremotev2.ui.screens.settings

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
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
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.WrapText
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocremotev2.R
import dev.leonardo.ocremotev2.ui.screens.settings.components.ImageCompressionMaxSideDialog
import dev.leonardo.ocremotev2.ui.screens.settings.components.ImageCompressionQualityDialog
import dev.leonardo.ocremotev2.ui.screens.settings.components.LanguagePickerDialog
import dev.leonardo.ocremotev2.ui.screens.settings.components.LocalServerLaunchOptionsDialog
import dev.leonardo.ocremotev2.ui.screens.settings.components.MessageCountPickerDialog
import dev.leonardo.ocremotev2.ui.screens.settings.components.PermissionRulesSection
import dev.leonardo.ocremotev2.ui.screens.settings.components.ReconnectModePickerDialog
import dev.leonardo.ocremotev2.ui.screens.settings.components.SectionHeader
import dev.leonardo.ocremotev2.ui.screens.settings.sections.AppearanceSection
import dev.leonardo.ocremotev2.ui.screens.settings.sections.GeneralSection
import dev.leonardo.ocremotev2.ui.screens.settings.components.TerminalFontSizeDialog
import dev.leonardo.ocremotev2.ui.screens.settings.components.ThemePickerDialog
import dev.leonardo.ocremotev2.ui.screens.settings.components.getImageMaxSideDisplayName
import dev.leonardo.ocremotev2.ui.screens.settings.components.getLanguageDisplayName
import dev.leonardo.ocremotev2.ui.screens.settings.components.getReconnectModeDisplayName
import dev.leonardo.ocremotev2.ui.screens.settings.components.getThemeDisplayName
import dev.leonardo.ocremotev2.ui.theme.AlphaTokens
import dev.leonardo.ocremotev2.ui.theme.ListItemTokens
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
    val currentLanguage by viewModel.appLanguage.collectAsStateWithLifecycle()
    val currentTheme by viewModel.appTheme.collectAsStateWithLifecycle()
    val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()
    val chatDensity by viewModel.chatDensity.collectAsStateWithLifecycle()
    val notificationsEnabled by viewModel.notificationsEnabled.collectAsStateWithLifecycle()

    val initialMessageCount by viewModel.initialMessageCount.collectAsStateWithLifecycle()
    val codeWordWrap by viewModel.codeWordWrap.collectAsStateWithLifecycle()
    val confirmBeforeSend by viewModel.confirmBeforeSend.collectAsStateWithLifecycle()
    val amoledDark by viewModel.amoledDark.collectAsStateWithLifecycle()
    val collapseTools by viewModel.collapseTools.collectAsStateWithLifecycle()
    val expandReasoning by viewModel.expandReasoning.collectAsStateWithLifecycle()
    val showTurnDividers by viewModel.showTurnDividers.collectAsStateWithLifecycle()
    val hapticFeedback by viewModel.hapticFeedback.collectAsStateWithLifecycle()
    val reconnectMode by viewModel.reconnectMode.collectAsStateWithLifecycle()
    val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle()
    val silentNotifications by viewModel.silentNotifications.collectAsStateWithLifecycle()
    val compressImageAttachments by viewModel.compressImageAttachments.collectAsStateWithLifecycle()
    val imageAttachmentMaxLongSide by viewModel.imageAttachmentMaxLongSide.collectAsStateWithLifecycle()
    val imageAttachmentWebpQuality by viewModel.imageAttachmentWebpQuality.collectAsStateWithLifecycle()
    val showLocalRuntime by viewModel.showLocalRuntime.collectAsStateWithLifecycle()
    val terminalFontSize by viewModel.terminalFontSize.collectAsStateWithLifecycle()
    val localProxyEnabled by viewModel.localProxyEnabled.collectAsStateWithLifecycle()
    val localProxyUrl by viewModel.localProxyUrl.collectAsStateWithLifecycle()
    val localProxyNoProxy by viewModel.localProxyNoProxy.collectAsStateWithLifecycle()
    val localServerAllowLan by viewModel.localServerAllowLan.collectAsStateWithLifecycle()
    val localServerUsername by viewModel.localServerUsername.collectAsStateWithLifecycle()
    val localServerPassword by viewModel.localServerPassword.collectAsStateWithLifecycle()
    val localServerRunInBackground by viewModel.localServerRunInBackground.collectAsStateWithLifecycle()
    val localServerAutoStart by viewModel.localServerAutoStart.collectAsStateWithLifecycle()
    val localServerStartupTimeoutSec by viewModel.localServerStartupTimeoutSec.collectAsStateWithLifecycle()
    val autoApproveRules by viewModel.autoApproveRules.collectAsStateWithLifecycle()

    var showLanguageDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showChatDensityPicker by remember { mutableStateOf(false) }
    var showMessageCountDialog by remember { mutableStateOf(false) }
    var showReconnectModeDialog by remember { mutableStateOf(false) }
    var showTerminalFontSizeDialog by remember { mutableStateOf(false) }
    var showImageMaxSideDialog by remember { mutableStateOf(false) }
    var showImageQualityDialog by remember { mutableStateOf(false) }
    var showLocalLaunchOptionsDialog by remember { mutableStateOf(false) }

    val switchColors = SwitchDefaults.colors()

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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
            // ======== General ========
            GeneralSection(
                viewModel = viewModel,
                onShowLanguageDialog = { showLanguageDialog = true },
                onShowReconnectModeDialog = { showReconnectModeDialog = true },
            )

            // ======== Appearance ========
            AppearanceSection(
                viewModel = viewModel,
                onShowThemeDialog = { showThemeDialog = true },
            )

            // ======== Chat Display ========
            SectionHeader(stringResource(R.string.settings_section_chat_display))

            // Chat font (density: normal / compact)
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_chat_font)) },
                supportingContent = {
                    Text(
                        if (chatDensity == "compact") stringResource(R.string.settings_chat_font_compact)
                        else stringResource(R.string.settings_chat_font_normal)
                    )
                },
                leadingContent = {
                    Icon(Icons.Default.FormatSize, contentDescription = stringResource(R.string.settings_chat_font))
                },
                modifier = Modifier.clickable { showChatDensityPicker = true }.padding(ListItemTokens.ContentPaddingMedium)
            )

            // Code word wrap
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_code_word_wrap)) },
                supportingContent = { Text(stringResource(R.string.settings_code_word_wrap_desc)) },
                leadingContent = {
                    Icon(Icons.Default.WrapText, contentDescription = stringResource(R.string.a11y_settings_code_word_wrap))
                },
                trailingContent = {
                    Switch(
                        checked = codeWordWrap,
                        onCheckedChange = { viewModel.setCodeWordWrap(it) },
                        colors = switchColors
                    )
                },
                modifier = Modifier.clickable { viewModel.setCodeWordWrap(!codeWordWrap) }.padding(ListItemTokens.ContentPaddingMedium)
            )

            // Auto-expand tool results
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_auto_expand_tools)) },
                supportingContent = { Text(stringResource(R.string.settings_auto_expand_tools_desc)) },
                leadingContent = {
                    Icon(Icons.Default.UnfoldMore, contentDescription = stringResource(R.string.a11y_settings_auto_expand_tools))
                },
                trailingContent = {
                    Switch(
                        checked = collapseTools,
                        onCheckedChange = { viewModel.setCollapseTools(it) },
                        colors = switchColors
                    )
                },
                modifier = Modifier.clickable { viewModel.setCollapseTools(!collapseTools) }.padding(ListItemTokens.ContentPaddingMedium)
            )

            // Expand reasoning by default
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_expand_reasoning)) },
                supportingContent = { Text(stringResource(R.string.settings_expand_reasoning_desc)) },
                leadingContent = {
                    Icon(Icons.Default.Psychology, contentDescription = stringResource(R.string.a11y_settings_expand_reasoning))
                },
                trailingContent = {
                    Switch(
                        checked = expandReasoning,
                        onCheckedChange = { viewModel.setExpandReasoning(it) },
                        colors = switchColors
                    )
                },
                modifier = Modifier.clickable { viewModel.setExpandReasoning(!expandReasoning) }.padding(ListItemTokens.ContentPaddingMedium)
            )

            // Show turn dividers
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_turn_dividers)) },
                supportingContent = { Text(stringResource(R.string.settings_turn_dividers_desc)) },
                leadingContent = {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                },
                trailingContent = {
                    Switch(
                        checked = showTurnDividers,
                        onCheckedChange = { viewModel.setShowTurnDividers(it) },
                        colors = switchColors
                    )
                },
                modifier = Modifier.clickable { viewModel.setShowTurnDividers(!showTurnDividers) }.padding(ListItemTokens.ContentPaddingMedium)
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ======== Chat Behavior ========
            SectionHeader(stringResource(R.string.settings_section_chat_behavior))

            // Initial message count
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_initial_messages)) },
                supportingContent = { Text("$initialMessageCount") },
                leadingContent = {
                    Icon(Icons.Default.Storage, contentDescription = stringResource(R.string.a11y_settings_initial_messages))
                },
                modifier = Modifier.clickable { showMessageCountDialog = true }.padding(ListItemTokens.ContentPaddingMedium)
            )

            // Confirm before send
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_confirm_send)) },
                supportingContent = { Text(stringResource(R.string.settings_confirm_send_desc)) },
                leadingContent = {
                    Icon(Icons.Default.Send, contentDescription = stringResource(R.string.a11y_settings_confirm_send))
                },
                trailingContent = {
                    Switch(
                        checked = confirmBeforeSend,
                        onCheckedChange = { viewModel.setConfirmBeforeSend(it) },
                        colors = switchColors
                    )
                },
                modifier = Modifier.clickable { viewModel.setConfirmBeforeSend(!confirmBeforeSend) }.padding(ListItemTokens.ContentPaddingMedium)
            )

            // Haptic feedback
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_haptic_feedback)) },
                supportingContent = { Text(stringResource(R.string.settings_haptic_feedback_desc)) },
                leadingContent = {
                    Icon(Icons.Default.Vibration, contentDescription = stringResource(R.string.a11y_settings_haptic_feedback))
                },
                trailingContent = {
                    Switch(
                        checked = hapticFeedback,
                        onCheckedChange = { viewModel.setHapticFeedback(it) },
                        colors = switchColors
                    )
                },
                modifier = Modifier.clickable { viewModel.setHapticFeedback(!hapticFeedback) }.padding(ListItemTokens.ContentPaddingMedium)
            )

            // Keep screen on
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_keep_screen_on)) },
                supportingContent = { Text(stringResource(R.string.settings_keep_screen_on_desc)) },
                leadingContent = {
                    Icon(Icons.Default.ScreenLockPortrait, contentDescription = stringResource(R.string.a11y_settings_keep_screen_on))
                },
                trailingContent = {
                    Switch(
                        checked = keepScreenOn,
                        onCheckedChange = { viewModel.setKeepScreenOn(it) },
                        colors = switchColors
                    )
                },
                modifier = Modifier.clickable { viewModel.setKeepScreenOn(!keepScreenOn) }.padding(ListItemTokens.ContentPaddingMedium)
            )

            // Optimize image attachments
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_compress_images)) },
                supportingContent = { Text(stringResource(R.string.settings_compress_images_desc)) },
                leadingContent = {
                    Icon(Icons.Default.PhotoSizeSelectLarge, contentDescription = stringResource(R.string.a11y_settings_compress_images))
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
                }.padding(ListItemTokens.ContentPaddingMedium)
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_compress_images_max_side)) },
                supportingContent = { Text(getImageMaxSideDisplayName(imageAttachmentMaxLongSide)) },
                leadingContent = {
                    Icon(Icons.Default.PhotoSizeSelectLarge, contentDescription = stringResource(R.string.a11y_settings_image_max_side))
                },
                modifier = Modifier.clickable(enabled = compressImageAttachments) { showImageMaxSideDialog = true }.padding(ListItemTokens.ContentPaddingMedium)
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_compress_images_quality)) },
                supportingContent = {
                    Text(stringResource(R.string.settings_compress_images_quality_value, imageAttachmentWebpQuality))
                },
                leadingContent = {
                    Icon(Icons.Default.PhotoSizeSelectLarge, contentDescription = stringResource(R.string.a11y_settings_image_quality))
                },
                modifier = Modifier.clickable(enabled = compressImageAttachments) { showImageQualityDialog = true }.padding(ListItemTokens.ContentPaddingMedium)
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_terminal_font_size)) },
                supportingContent = {
                    Text(stringResource(R.string.settings_terminal_font_size_value, terminalFontSize.roundToInt()))
                },
                leadingContent = {
                    Icon(Icons.Default.Terminal, contentDescription = stringResource(R.string.a11y_settings_terminal_font_size))
                },
                modifier = Modifier.clickable { showTerminalFontSizeDialog = true }.padding(ListItemTokens.ContentPaddingMedium)
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ======== Advanced ========
            SectionHeader(stringResource(R.string.settings_section_advanced))

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_local_runtime)) },
                supportingContent = { Text(stringResource(R.string.settings_local_runtime_desc)) },
                leadingContent = {
                    Icon(Icons.Default.Code, contentDescription = stringResource(R.string.a11y_settings_local_runtime))
                },
                trailingContent = {
                    Switch(
                        checked = showLocalRuntime,
                        onCheckedChange = { viewModel.setShowLocalRuntime(it) },
                        colors = switchColors,
                    )
                },
                modifier = Modifier.clickable { viewModel.setShowLocalRuntime(!showLocalRuntime) }.padding(ListItemTokens.ContentPaddingMedium),
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.home_local_launch_options)) },
                supportingContent = { Text(stringResource(R.string.home_local_launch_options_desc)) },
                leadingContent = {
                    Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.a11y_settings_launch_options))
                },
                modifier = Modifier.clickable { showLocalLaunchOptionsDialog = true }.padding(ListItemTokens.ContentPaddingMedium),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ======== Notifications ========
            SectionHeader(stringResource(R.string.settings_section_notifications))

            // Notifications
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_notifications)) },
                supportingContent = { Text(stringResource(R.string.settings_notifications_desc)) },
                leadingContent = {
                    Icon(Icons.Default.Notifications, contentDescription = stringResource(R.string.a11y_settings_notifications))
                },
                trailingContent = {
                    Switch(
                        checked = notificationsEnabled,
                        onCheckedChange = { viewModel.setNotificationsEnabled(it) },
                        colors = switchColors
                    )
                },
                modifier = Modifier.clickable { viewModel.setNotificationsEnabled(!notificationsEnabled) }.padding(ListItemTokens.ContentPaddingMedium)
            )

            // Silent notifications
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_silent_notifications)) },
                supportingContent = { Text(stringResource(R.string.settings_silent_notifications_desc)) },
                leadingContent = {
                    Icon(Icons.Default.NotificationsOff, contentDescription = stringResource(R.string.a11y_settings_silent_notifications))
                },
                trailingContent = {
                    Switch(
                        checked = silentNotifications,
                        onCheckedChange = { viewModel.setSilentNotifications(it) },
                        colors = switchColors
                    )
                },
                modifier = Modifier.clickable { viewModel.setSilentNotifications(!silentNotifications) }.padding(ListItemTokens.ContentPaddingMedium)
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ======== Permissions ========
            SectionHeader(stringResource(R.string.settings_auto_approve_rules))
            PermissionRulesSection(
                rules = autoApproveRules,
                onDeleteRule = { rule -> viewModel.deletePermissionRule(rule) }
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

        if (showChatDensityPicker) {
            AlertDialog(
                onDismissRequest = { showChatDensityPicker = false },
                title = { Text(stringResource(R.string.settings_chat_font)) },
                text = {
                    Column {
                        listOf(
                            "normal" to R.string.settings_chat_font_normal,
                            "compact" to R.string.settings_chat_font_compact
                        ).forEach { (value, labelRes) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.setChatDensity(value)
                                        showChatDensityPicker = false
                                    }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = chatDensity == value,
                                    onClick = null,
                                )
                                Text(
                                    stringResource(labelRes),
                                    modifier = Modifier.padding(start = 12.dp)
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showChatDensityPicker = false }) {
                        Text(stringResource(R.string.close))
                    }
                },
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
}
