package dev.leonardo.ocremotev2.ui.screens.settings.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.leonardo.ocremotev2.R
import java.util.Locale

@Composable
internal fun getThemeDisplayName(theme: String): String {
    return when (theme) {
        "system" -> stringResource(R.string.settings_theme_system)
        "light" -> stringResource(R.string.settings_theme_light)
        "dark" -> stringResource(R.string.settings_theme_dark)
        else -> theme
    }
}

@Composable
internal fun getFontSizeDisplayName(size: String): String {
    return when (size) {
        "small" -> stringResource(R.string.settings_font_size_small)
        "medium" -> stringResource(R.string.settings_font_size_medium)
        "large" -> stringResource(R.string.settings_font_size_large)
        else -> size
    }
}

@Composable
internal fun getLanguageDisplayName(code: String): String {
    val systemDefault = stringResource(R.string.settings_language_system)

    if (code.isEmpty()) return systemDefault

    // Parse the language tag and get native display name
    val locale = if (code.contains("-")) {
        val parts = code.split("-")
        if (parts.size >= 2) {
            Locale(parts[0], parts[1].uppercase())
        } else {
            Locale(parts[0])
        }
    } else {
        Locale(code)
    }

    return locale.getDisplayName(locale).replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(locale) else it.toString()
    }
}

@Composable
internal fun getReconnectModeDisplayName(mode: String): String {
    return when (mode) {
        "aggressive" -> stringResource(R.string.settings_reconnect_aggressive)
        "normal" -> stringResource(R.string.settings_reconnect_normal)
        "conservative" -> stringResource(R.string.settings_reconnect_conservative)
        else -> mode
    }
}

@Composable
internal fun getImageMaxSideDisplayName(px: Int): String {
    if (px <= 0) {
        return stringResource(R.string.settings_compress_images_max_side_keep_original)
    }
    return stringResource(R.string.settings_compress_images_max_side_value, px)
}
