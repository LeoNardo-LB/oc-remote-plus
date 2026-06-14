package dev.minios.ocremote.ui.screens.chat.util

/**
 * Pure function for checking whether a reverseLayout LazyColumn is at the bottom.
 *
 * In reverseLayout=true, LazyListItemInfo.offset for item 0:
 * - Equals 0 when at bottom (scroll position = 0)
 * - Becomes negative when user scrolls to see older messages (forward scroll, delta < 0)
 *
 * The old code used `offset <= 64` which incorrectly included ALL negative values,
 * causing isAtBottom to return true even when the user had scrolled away.
 *
 * Correct logic: offset must be >= -tolerance (allowing tiny layout jitter)
 * AND <= +tolerance (allowing slight overscroll position).
 */
object ScrollPositionChecker {

    fun isAtBottom(
        firstVisibleIndex: Int,
        firstVisibleOffset: Int,
        totalItemsCount: Int,
        tolerance: Int = 1
    ): Boolean {
        if (totalItemsCount == 0) return true
        if (firstVisibleIndex < 0) return false
        return firstVisibleIndex == 0 &&
            firstVisibleOffset >= -tolerance &&
            firstVisibleOffset <= tolerance
    }
}
