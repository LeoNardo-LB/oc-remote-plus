package dev.minios.ocremote.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Centralized button style tokens.
 *
 * All FilledTonalButton colors, borders, and content padding values are defined here.
 * Change once, apply everywhere.
 *
 * Usage:
 * ```kotlin
 * FilledTonalButton(
 *     colors = ButtonTokens.tonalColors(),
 *     border = ButtonTokens.tonalBorder(),
 *     contentPadding = ButtonTokens.CompactPadding,
 * )
 * ```
 */
object ButtonTokens {

    // ── Content Padding ──────────────────────────────────────────────

    /** Compact vertical padding for full-width stacked buttons (3+ in a Column). */
    val CompactPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)

    /** Spacing between stacked full-width buttons in a Column. */
    const val StackSpacing = 4

    /** Spacing between inline buttons in a Row. */
    const val RowSpacing = 8

    // ── FilledTonalButton Colors ──────────────────────────────────────

    /**
     * Colors for [FilledTonalButton] adapted to the current theme.
     *
     * - **Light / Dark**: primary / onPrimary — deep saturated container with white text.
     * - **AMOLED**: Black container + primary content.
     */
    @Composable
    fun tonalColors(): ButtonColors {
        val isAmoled = LocalAmoledMode.current
        return if (isAmoled) {
            ButtonDefaults.filledTonalButtonColors(
                containerColor = Color.Black,
                contentColor = MaterialTheme.colorScheme.primary,
            )
        } else {
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }

    // ── FilledTonalButton Border ──────────────────────────────────────

    /**
     * Border for [FilledTonalButton] adapted to the current theme.
     *
     * - **AMOLED**: 1dp primary border with [AlphaTokens.HIGH] alpha.
     * - **Light / Dark**: `null` (no border).
     */
    @Composable
    fun tonalBorder(): BorderStroke? {
        val isAmoled = LocalAmoledMode.current
        return if (isAmoled) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.HIGH))
        } else {
            null
        }
    }
}
