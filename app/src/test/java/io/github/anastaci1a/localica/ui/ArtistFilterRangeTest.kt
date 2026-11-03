package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.Job
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class ArtistFilterRangeTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun suspendedCorrectionReleasesScrollFlagBeforeQueuedInterruptionCleanup() = runTest {
        val owner = ArtistFilterRange()
        val request = requireNotNull(owner.begin(-432, 600, Any()))
        val ownership = BrowseScrollOwnership()
        val clock = BroadcastFrameClock()
        val deltas = mutableListOf<Float>()
        val state = ScrollableState { deltas += it; it }
        val correction = launch(clock) { owner.correct(request, state, ownership, -200f, false) }
        runCurrent()
        clock.sendFrame(0); runCurrent()
        clock.sendFrame(32_000_000); runCurrent()
        assertTrue(deltas.sum() < 0f)
        assertTrue(correction.isActive)
        assertFalse(state.isScrollInProgress)
        assertFalse(ownership.internal)
        val before = deltas.toList()
        owner.interrupt()
        // Deliberately do not drain the queued cancellation before observing the input boundary.
        assertFalse(state.isScrollInProgress)
        assertFalse(ownership.internal)
        clock.sendFrame(64_000_000); runCurrent()
        assertEquals(before, deltas)
        assertTrue(correction.isCancelled)
        assertSame(request, owner.request)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun competingScrollBetweenFramesIsNeverCancelledByCorrection() = runTest {
        val owner = ArtistFilterRange()
        val request = requireNotNull(owner.begin(-432, 600, Any()))
        val clock = BroadcastFrameClock()
        val deltas = mutableListOf<Float>()
        val state = ScrollableState { deltas += it; it }
        val correction = launch(clock) { owner.correct(request, state, BrowseScrollOwnership(), -200f, false) }
        runCurrent()
        clock.sendFrame(0); runCurrent()
        val user = launch { state.scroll { scrollBy(17f); awaitCancellation() } }
        runCurrent()
        val before = deltas.toList()
        clock.sendFrame(32_000_000); runCurrent()
        assertTrue(user.isActive)
        assertTrue(state.isScrollInProgress)
        assertTrue(correction.isCancelled)
        assertEquals(before, deltas)
        user.cancel(); runCurrent()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun correctionCarriesUnconsumedDistanceAndFinishesOnOriginalCurve() = runTest {
        val owner = ArtistFilterRange()
        val request = requireNotNull(owner.begin(-432, 600, Any()))
        val clock = BroadcastFrameClock()
        var consumed = 0f
        var partial = true
        val state = ScrollableState { delta ->
            (if (partial) delta / 2f else delta).also { consumed += it }
        }
        val correction = launch(clock) { owner.correct(request, state, BrowseScrollOwnership(), -200f, false) }
        runCurrent()
        clock.sendFrame(0); runCurrent()
        clock.sendFrame(140_000_000); runCurrent()
        val halfway = androidx.compose.animation.core.LinearOutSlowInEasing.transform(.5f) * -200f
        assertEquals(halfway / 2f, consumed, .01f)
        assertFalse(state.isScrollInProgress)
        partial = false
        clock.sendFrame(280_000_000); runCurrent()
        assertEquals(-200f, consumed, .01f)
        assertTrue(correction.isCompleted)
        assertFalse(state.isScrollInProgress)
    }

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
        val oldMotion = Job()
        assertTrue(owner.bindMotion(old, oldMotion))
        val latest = requireNotNull(owner.begin(-390, 600, Any()))
        assertTrue("Replacement cancels old motion before begin returns", oldMotion.isCancelled)
        assertFalse(owner.owns(old))
        val staleMotion = Job()
        assertFalse(owner.bindMotion(old, staleMotion))
        assertTrue(staleMotion.isCancelled)
        val latestMotion = Job()
        assertTrue(owner.bindMotion(latest, latestMotion))
        owner.releaseMotion(oldMotion)
        owner.finish(old)
        assertSame(latest, owner.request)
        val before = owner.interruption
        owner.interrupt()
        assertTrue("Touch cancels the owned motion immediately", latestMotion.isCancelled)
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
