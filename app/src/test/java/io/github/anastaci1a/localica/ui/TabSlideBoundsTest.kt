package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test

/** #728: slide bounds follow the bar's fit/overflow decision, not stale scroll state. */
class TabSlideBoundsTest {
    @Test fun fittingBoundsIgnoreAScrollOffsetLeftFromAnEarlierOverflow() {
        assertEquals(0f..3f, tabSlideBounds(fits = true, scroll = 747f, viewport = 743f, cell = 185.75f, lastIndex = 3))
        assertEquals(0f..3f, tabSlideBounds(fits = true, scroll = 0f, viewport = 743f, cell = 185.75f, lastIndex = 3))
    }

    @Test fun overflowingBoundsAreTheVisibleCells() {
        val bounds = tabSlideBounds(fits = false, scroll = 248.4f, viewport = 743f, cell = 248.4f, lastIndex = 5)
        assertEquals(1f, bounds.start, 1e-4f)
        assertEquals(743f / 248.4f, bounds.endInclusive, 1e-4f)
        assertEquals(3f..3f, tabSlideBounds(fits = false, scroll = 5000f, viewport = 743f, cell = 248.4f, lastIndex = 3))
    }
}
