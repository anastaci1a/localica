package io.github.anastaci1a.localica.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #715: the return arrow shows only while a return to the current line would move the list. */
class LyricReturnBoundsTest {
    private val tolerance = 2

    @Test fun midListIsUnchanged() {
        // Away from both measured bounds the arrow keeps its pre-#715 behaviour, even when the line happens to be centred.
        assertTrue(lyricReturnMoves(0, canScrollBackward = true, canScrollForward = true, tolerance))
        assertTrue(lyricReturnMoves(-300, canScrollBackward = true, canScrollForward = true, tolerance))
        assertTrue(lyricReturnMoves(300, canScrollBackward = true, canScrollForward = true, tolerance))
    }

    @Test fun currentLineOffScreenAlwaysReturns() {
        assertTrue(lyricReturnMoves(null, canScrollBackward = false, canScrollForward = true, tolerance))
        assertTrue(lyricReturnMoves(null, canScrollBackward = true, canScrollForward = false, tolerance))
    }

    @Test fun topBoundHidesWhenTheReturnLandsThere() {
        // The first line is centred at the top bound, or the return would scroll further up than the bound allows.
        assertFalse(lyricReturnMoves(0, canScrollBackward = false, canScrollForward = true, tolerance))
        assertFalse(lyricReturnMoves(1, canScrollBackward = false, canScrollForward = true, tolerance))
        assertFalse(lyricReturnMoves(-40, canScrollBackward = false, canScrollForward = true, tolerance))
        // A later current line: the return scrolls forward from the top bound.
        assertTrue(lyricReturnMoves(60, canScrollBackward = false, canScrollForward = true, tolerance))
    }

    @Test fun bottomBoundHidesWhenTheReturnLandsThere() {
        assertFalse(lyricReturnMoves(0, canScrollBackward = true, canScrollForward = false, tolerance))
        assertFalse(lyricReturnMoves(-2, canScrollBackward = true, canScrollForward = false, tolerance))
        assertFalse(lyricReturnMoves(40, canScrollBackward = true, canScrollForward = false, tolerance))
        // An earlier current line: the return scrolls backward from the bottom bound.
        assertTrue(lyricReturnMoves(-60, canScrollBackward = true, canScrollForward = false, tolerance))
    }

    @Test fun listShorterThanItsViewportNeverMoves() {
        assertFalse(lyricReturnMoves(30, canScrollBackward = false, canScrollForward = false, tolerance))
        assertFalse(lyricReturnMoves(-30, canScrollBackward = false, canScrollForward = false, tolerance))
    }
}
