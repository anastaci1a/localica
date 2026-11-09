package io.github.anastaci1a.localica.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test

/** #728: one fit/overflow decision, and slide bounds that follow it. */
class TabStripLayoutTest {
    @Test fun fourTabsFitTheA35PillAtNormalFontScales() {
        val pill = (1080f / (397f / 160f) - 136f).dp
        val layout = tabStripLayout(pill, 76.dp, 4, .9f)
        assertTrue(layout.fits)
        assertEquals(pill / 4, layout.cell)
    }

    @Test fun largeFontOverflowsAtTheMeasuredLabelWidth() {
        val layout = tabStripLayout(299.dp, 175.dp, 4, 2f)
        assertFalse(layout.fits)
        assertEquals(207.dp, layout.cell)
    }

    @Test fun moreTabsThanFitOverflowAtTheMinimumCell() {
        val layout = tabStripLayout(299.dp, 20.dp, 6, 1f)
        assertFalse(layout.fits)
        assertEquals(68.dp, layout.cell)
    }

    @Test fun fittingBoundsIgnoreAScrollOffsetLeftFromAnEarlierOverflow() {
        assertEquals(0f..3f, tabSlideBounds(fits = true, scroll = 747f, viewport = 743f, cell = 185.75f, lastIndex = 3))
    }

    @Test fun overflowingBoundsAreTheVisibleCells() {
        val bounds = tabSlideBounds(fits = false, scroll = 248.4f, viewport = 743f, cell = 248.4f, lastIndex = 5)
        assertEquals(1f, bounds.start, 1e-4f)
        assertEquals(743f / 248.4f, bounds.endInclusive, 1e-4f)
        assertEquals(3f..3f, tabSlideBounds(fits = false, scroll = 5000f, viewport = 743f, cell = 248.4f, lastIndex = 3))
    }
}
