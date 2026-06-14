package dev.minios.ocremote.ui.screens.chat.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollPositionCheckerTest {

    @Test
    fun `reverseLayout - empty list is at bottom`() {
        val result = ScrollPositionChecker.isAtBottom(
            firstVisibleIndex = -1,
            firstVisibleOffset = 0,
            totalItemsCount = 0,
            tolerance = 1
        )
        assertEquals(true, result)
    }

    @Test
    fun `reverseLayout - item 0 at offset 0 is at bottom`() {
        val result = ScrollPositionChecker.isAtBottom(
            firstVisibleIndex = 0,
            firstVisibleOffset = 0,
            totalItemsCount = 10,
            tolerance = 1
        )
        assertEquals(true, result)
    }

    @Test
    fun `reverseLayout - item 0 at offset minus 1 (layout jitter) is at bottom`() {
        val result = ScrollPositionChecker.isAtBottom(
            firstVisibleIndex = 0,
            firstVisibleOffset = -1,
            totalItemsCount = 10,
            tolerance = 1
        )
        assertEquals(true, result)
    }

    @Test
    fun `reverseLayout - item 0 at offset minus 10 (user scrolled) is NOT at bottom`() {
        val result = ScrollPositionChecker.isAtBottom(
            firstVisibleIndex = 0,
            firstVisibleOffset = -10,
            totalItemsCount = 10,
            tolerance = 1
        )
        assertEquals(false, result)
    }

    @Test
    fun `reverseLayout - item 0 at offset minus 223 (user scrolled far) is NOT at bottom`() {
        val result = ScrollPositionChecker.isAtBottom(
            firstVisibleIndex = 0,
            firstVisibleOffset = -223,
            totalItemsCount = 10,
            tolerance = 1
        )
        assertEquals(false, result)
    }

    @Test
    fun `reverseLayout - item 1 visible (item 0 gone) is NOT at bottom`() {
        val result = ScrollPositionChecker.isAtBottom(
            firstVisibleIndex = 1,
            firstVisibleOffset = 0,
            totalItemsCount = 10,
            tolerance = 1
        )
        assertEquals(false, result)
    }

    @Test
    fun `reverseLayout - item 0 at offset 50 within tolerance 64 is at bottom`() {
        val result = ScrollPositionChecker.isAtBottom(
            firstVisibleIndex = 0,
            firstVisibleOffset = 50,
            totalItemsCount = 10,
            tolerance = 64
        )
        assertEquals(true, result)
    }

    @Test
    fun `reverseLayout - item 0 at offset 100 beyond tolerance 64 is NOT at bottom`() {
        val result = ScrollPositionChecker.isAtBottom(
            firstVisibleIndex = 0,
            firstVisibleOffset = 100,
            totalItemsCount = 10,
            tolerance = 64
        )
        assertEquals(false, result)
    }
}
