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
internal fun LanguagePickerDialog(
    currentLanguage: String,
    onLanguageSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val systemDefault = stringResource(R.string.settings_language_system)

    AppDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.dialog_select_language),
        showClose = false,
        showDividers = false,
        scrollable = false,
        maxBodyHeight = 520.dp,
        content = {
            AppPickerList(
                options = listOf(
                    "" to systemDefault,
                    "en" to "English",
                    "ar" to "العربية",
                    "de" to "Deutsch",
                    "es" to "Español",
                    "fr" to "Français",
                    "id" to "Bahasa Indonesia",
                    "it" to "Italiano",
                    "ja" to "日本語",
                    "ko" to "한국어",
                    "pl" to "Polski",
                    "pt-BR" to "Português (Brasil)",
                    "ru" to "Русский",
                    "tr" to "Türkçe",
                    "uk" to "Українська",
                    "zh-CN" to "简体中文"
                ),
                selectedKey = currentLanguage,
                onSelect = onLanguageSelected,
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
