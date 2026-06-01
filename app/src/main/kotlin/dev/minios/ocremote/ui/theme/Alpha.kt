package dev.minios.ocremote.ui.theme

/**
 * Semantic alpha tokens for consistent content emphasis across the app.
 *
 * Usage: `colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT)`
 *
 * Token scale:
 *   FAINT  (0.35) — metadata, timestamps, placeholder, subtle dividers
 *   MUTED  (0.50) — secondary text, subtle icons, secondary borders
 *   MEDIUM (0.70) — tertiary content, standard borders, primary text variants
 *   HIGH   (0.80) — control borders, selected indicators, card borders
 *   AMOLED (0.92) — AMOLED mode code text max contrast
 */
object AlphaTokens {
    const val FAINT = 0.35f
    const val MUTED = 0.50f
    const val MEDIUM = 0.70f
    const val HIGH = 0.80f
    const val AMOLED = 0.92f
}
