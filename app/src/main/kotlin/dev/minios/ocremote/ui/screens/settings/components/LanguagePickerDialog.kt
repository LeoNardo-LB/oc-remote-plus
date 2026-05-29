package dev.minios.ocremote.ui.screens.settings.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.minios.ocremote.R

@Composable
internal fun LanguagePickerDialog(
    currentLanguage: String,
    onLanguageSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val systemDefault = stringResource(R.string.settings_language_system)

    SettingsPickerDialog(
        title = stringResource(R.string.dialog_select_language),
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
        onDismiss = onDismiss,
        maxHeight = 520
    )
}
