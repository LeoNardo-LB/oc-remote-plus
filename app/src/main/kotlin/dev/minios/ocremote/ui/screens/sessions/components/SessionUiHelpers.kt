package dev.minios.ocremote.ui.screens.sessions.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Check if the current theme is AMOLED dark mode.
 */
@Composable
internal fun isAmoledTheme(): Boolean {
    val colors = MaterialTheme.colorScheme
    return colors.background == Color.Black && colors.surface == Color.Black
}
