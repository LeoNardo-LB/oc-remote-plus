package dev.minios.ocremote.ui.screens.chat.util

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll

/**
 * Perform a light haptic tick if haptic feedback is enabled.
 * Call from composable context or from a click lambda that has access to a View.
 */
@Suppress("DEPRECATION")
internal fun performHaptic(view: View, enabled: Boolean) {
    if (enabled) {
        view.performHapticFeedback(
            HapticFeedbackConstants.CLOCK_TICK,
            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }
}

/**
 * Conditionally applies horizontalScroll for code blocks.
 * When word wrap is enabled, no horizontal scroll is applied.
 */
@Composable
internal fun Modifier.codeHorizontalScroll(): Modifier {
    return if (!LocalCodeWordWrap.current) {
        this.horizontalScroll(rememberScrollState())
    } else {
        this
    }
}

/**
 * Prevents nested-scroll inertia AND drag from propagating to the parent when
 * the child scrollable reaches its boundary.
 *
 * Consumes remaining delta in `onPostScroll` (finger drag) and remaining
 * velocity in `onPostFling` (inertia) when [ScrollState.canScrollForward] or
 * [ScrollState.canScrollBackward] is false.
 */
@Composable
internal fun Modifier.consumeBoundaryScroll(scrollState: ScrollState): Modifier {
    val connection = remember(scrollState) {
        object : NestedScrollConnection {
            /** Finger drag reached boundary — consume remaining delta, block propagation to parent */
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                val atTop = !scrollState.canScrollBackward
                val atBottom = !scrollState.canScrollForward
                return when {
                    atTop && available.y < 0f -> available
                    atBottom && available.y > 0f -> available
                    else -> Offset.Zero
                }
            }

            /** Inertia fling reached boundary — consume remaining velocity, block propagation to parent */
            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity
            ): Velocity {
                val atTop = !scrollState.canScrollBackward
                val atBottom = !scrollState.canScrollForward
                return when {
                    atTop && available.y < 0f -> available
                    atBottom && available.y > 0f -> available
                    else -> Velocity.Zero
                }
            }
        }
    }
    return this.nestedScroll(connection)
}
