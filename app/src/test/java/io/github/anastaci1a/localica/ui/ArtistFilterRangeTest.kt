package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test

class ArtistFilterRangeTest {
    @Test fun measuredRangeExcessNeverMeansUnconditionalTopOrMovementOfValidOffsets() {
        assertEquals(0f, ArtistFilterRange.excess(null, 600, 80), 0f)
        assertEquals(0f, ArtistFilterRange.excess(600, 600, 80), 0f)
        assertEquals(0f, ArtistFilterRange.excess(520, 600, 80), 0f)
        assertEquals(-200f, ArtistFilterRange.excess(320, 600, 80), 0f)
        // Current offset500 becomes300, NOT zero, when only200 pixels exceed the new range.
        assertEquals(300f, 500 + ArtistFilterRange.excess(320, 600, 80), 0f)
        assertEquals(-700f, ArtistFilterRange.excess(-180, 600, 80), 0f)
    }

    @Test fun temporaryExtentPreservesExactPixelAnchorBeforeNewRowsAreMeasured() {
        val owner = ArtistFilterRange()
        val layout = Any()
        val request = requireNotNull(owner.begin(-432, 600, layout))
        assertEquals(432, request.scrollOffset)
        assertEquals(1032, request.reservePx)
        assertSame(layout, request.previousLayout)
        assertTrue(owner.owns(request))
        owner.finish(request)
        assertNull(owner.request)
    }

    @Test fun rapidChangesAndGestureInterruptionRetireOnlyTheOwningRequest() {
        val owner = ArtistFilterRange()
        val old = requireNotNull(owner.begin(-432, 600, Any()))
        val latest = requireNotNull(owner.begin(-390, 600, Any()))
        assertFalse(owner.owns(old))
        owner.finish(old)
        assertSame(latest, owner.request)
        val before = owner.interruption
        owner.interrupt()
        assertNotSame(before, owner.interruption)
        assertSame("Touch pauses reconciliation without dropping the extent and flashing a clamp", latest, owner.request)
        owner.finish(latest)
        assertNull(owner.request)
    }

    @Test fun missingHeaderUnlaidOutViewportOrOverflowCannotGuessAnAbsoluteOffset() {
        val owner = ArtistFilterRange()
        assertNull(owner.begin(null, 600, Any()))
        assertNull(owner.begin(-30, 0, Any()))
        assertNull(owner.begin(Int.MIN_VALUE, 600, Any()))
        val atStart = requireNotNull(owner.begin(0, 600, Any()))
        assertEquals(0, atStart.scrollOffset)
    }
}
