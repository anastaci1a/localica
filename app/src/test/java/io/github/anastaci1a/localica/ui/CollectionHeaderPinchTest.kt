package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.ui.CollectionHeaderPinch.Endpoint
import io.github.anastaci1a.localica.ui.CollectionHeaderPinch.Offset
import io.github.anastaci1a.localica.ui.CollectionHeaderPinch.Rect
import org.junit.Assert.*
import org.junit.Test

class CollectionHeaderPinchTest {
    private val source = Rect(0f, -120f, 400f, 640f)
    private val destination = Rect(0f, -360f, 400f, 400f)

    @Test fun forwardReverseAndPartialReversalUseTheSameExistingProgress() {
        for ((start, end) in listOf(source to destination, destination to source)) {
            val pinch = CollectionHeaderPinch()
            assertTrue(pinch.begin(0, start))
            assertTrue(pinch.updateDestination(0, end))
            for (p in listOf(0f, .2f, .7f, 1f, .7f, .2f, 0f)) {
                val frame = requireNotNull(pinch.frame(0, p, end))
                assertEquals(start.top * (1f - p) + end.top * p, frame.presented.top, .0001f)
                assertEquals(start.bottom * (1f - p) + end.bottom * p, frame.presented.bottom, .0001f)
                assertEquals(frame.presented.top, end.top + requireNotNull(frame.liveOffset).y, .0001f)
                assertEquals(0f, frame.liveOffset.x, 0f)
                assertTrue(frame.destinationReady)
            }
            assertEquals(start, pinch.frame(0, -1f, end)?.presented)
            assertEquals(end, pinch.frame(0, 2f, end)?.presented)
        }
    }

    @Test fun missingDestinationHoldsSourceWithoutInventingLiveCoordinates() {
        val pinch = CollectionHeaderPinch()
        assertNull(pinch.frame(0, .5f, null))
        pinch.begin(0, source)
        for (p in listOf(0f, .5f, 1f)) {
            val frame = requireNotNull(pinch.frame(0, p, null))
            assertEquals(source, frame.presented)
            assertNull(frame.liveOffset)
            assertFalse(frame.destinationReady)
        }
        assertFalse(pinch.beginHandoff(0, Endpoint.Destination))
        assertFalse(pinch.retireAfterHandoff(0, 1f, source))
        assertFalse(pinch.begin(0, destination))
        pinch.updateDestination(0, destination)
        assertEquals(source, pinch.frame(0, 0f, destination)?.presented)
        assertEquals(destination, pinch.frame(0, 1f, destination)?.presented)
    }

    @Test fun correctedDestinationNeverRecapturesSourceAndLiveOffsetUsesCurrentPlacement() {
        val pinch = CollectionHeaderPinch()
        pinch.begin(2, source)
        pinch.updateDestination(2, destination)
        val corrected = Rect(8f, -380f, 408f, 380f)
        assertTrue(pinch.updateDestination(2, corrected))
        assertEquals(source, pinch.frame(2, 0f, corrected)?.presented)
        val frame = requireNotNull(pinch.frame(2, .5f, destination))
        assertEquals(Rect(4f, -250f, 404f, 510f), frame.presented)
        assertEquals(Offset(4f, 110f), frame.liveOffset)
        assertEquals(Offset(0f, 0f), pinch.frame(2, 1f, corrected)?.liveOffset)
    }

    @Test fun destinationPublicationAtZeroProgressPreservesTheSourceBeforeTheClockAdvances() {
        val pinch = CollectionHeaderPinch()
        pinch.begin(0, source)
        val before = requireNotNull(pinch.frame(0, 0f, source))
        assertFalse(before.destinationReady)
        pinch.updateDestination(0, destination)
        val ready = requireNotNull(pinch.frame(0, 0f, destination))
        assertTrue(ready.destinationReady)
        assertEquals(before.presented, ready.presented)
        assertEquals(before.presented.top,
            destination.top + requireNotNull(ready.liveOffset).y, 0f)
        assertEquals(-144f, pinch.frame(0, .1f, destination)?.presented?.top ?: Float.NaN, .0001f)
    }

    @Test fun presentedBoundsDescribeSizeWithoutInventingALiveScale() {
        val pinch = CollectionHeaderPinch()
        val target = Rect(20f, -400f, 380f, 300f)
        pinch.begin(0, source)
        pinch.updateDestination(0, target)
        val frame = requireNotNull(pinch.frame(0, .5f, target))
        assertEquals(Rect(10f, -260f, 390f, 470f), frame.presented)
        assertEquals(Offset(-10f, 140f), frame.liveOffset)
        assertEquals(frame.presented, pinch.frame(0, .5f, null)?.presented)
    }

    @Test fun retainedAndMountedDrawingsHaveIdenticalPresentedEndpointsAndTrajectory() {
        val pinch = CollectionHeaderPinch()
        pinch.begin(3, source)
        pinch.updateDestination(3, destination)
        for (p in listOf(0f, .3f, 1f, .6f, 0f)) {
            val retained = requireNotNull(pinch.frame(3, p, null))
            val mounted = requireNotNull(pinch.frame(3, p, destination))
            assertEquals(retained.presented, mounted.presented)
            assertNull(retained.liveOffset)
            assertEquals(retained.presented.top,
                destination.top + requireNotNull(mounted.liveOffset).y, .0001f)
        }
    }

    @Test fun commitKeepsOwnershipUntilDestinationIsMountedAtSettledGeometry() {
        val pinch = CollectionHeaderPinch()
        pinch.begin(4, source)
        pinch.updateDestination(4, destination)
        assertFalse("Finger-up is not a handoff", pinch.retireAfterHandoff(4, 1f, destination))
        assertTrue(pinch.beginHandoff(4, Endpoint.Destination))
        assertTrue(pinch.beginHandoff(4, Endpoint.Destination))
        assertFalse(pinch.beginHandoff(4, Endpoint.Source))
        assertFalse(pinch.retireAfterHandoff(4, .9f, destination))
        assertFalse(pinch.retireAfterHandoff(4, 1f, null))
        assertFalse(pinch.retireAfterHandoff(4, 1f, source))
        assertFalse(pinch.retireAfterHandoff(4, 1f, destination.copy(top = destination.top + 1f)))
        assertEquals(Offset(0f, 0f), pinch.frame(4, 1f, destination)?.liveOffset)
        assertTrue(pinch.retireAfterHandoff(4, 1f, destination))
        assertNull(pinch.frame(4, 1f, destination))
        assertFalse(pinch.begin(4, source))
    }

    @Test fun rollbackUsesOriginalSourceUntilRemountAndCannotOverwriteDestination() {
        val pinch = CollectionHeaderPinch()
        pinch.begin(5, source)
        pinch.updateDestination(5, destination)
        assertTrue(pinch.beginHandoff(5, Endpoint.Source))
        assertFalse(pinch.updateDestination(5, source))
        assertEquals(destination, pinch.frame(5, 1f, destination)?.presented)
        assertFalse(pinch.retireAfterHandoff(5, .1f, source))
        assertFalse(pinch.retireAfterHandoff(5, 0f, destination))
        assertFalse(pinch.retireAfterHandoff(5, 0f, null))
        val beforeRemount = requireNotNull(pinch.frame(5, 0f, destination))
        val remounted = requireNotNull(pinch.frame(5, 0f, source))
        assertEquals(beforeRemount.presented, remounted.presented)
        assertEquals(Offset(0f, 240f), beforeRemount.liveOffset)
        assertEquals(Offset(0f, 0f), remounted.liveOffset)
        assertTrue(pinch.retireAfterHandoff(5, 0f, source))
        assertNull(pinch.frame(5, 0f, source))
    }

    @Test fun sourceHandoffCanRetireWithoutEverInventingADestination() {
        val pinch = CollectionHeaderPinch()
        pinch.begin(6, source)
        assertTrue(pinch.beginHandoff(6, Endpoint.Source))
        assertFalse(pinch.updateDestination(6, destination))
        assertEquals(source, pinch.frame(6, 0f, source)?.presented)
        assertTrue(pinch.retireAfterHandoff(6, 0f, source))
    }

    @Test fun staleCallbacksCannotMutateRetireDiscardOrResurrectANewerGeneration() {
        val pinch = CollectionHeaderPinch()
        pinch.begin(7, source)
        pinch.updateDestination(7, destination)
        pinch.beginHandoff(7, Endpoint.Destination)
        assertTrue(pinch.begin(8, destination))
        assertFalse(pinch.updateDestination(7, source))
        assertFalse(pinch.beginHandoff(7, Endpoint.Source))
        assertFalse(pinch.retireAfterHandoff(7, 1f, destination))
        assertFalse(pinch.discard(7))
        assertFalse(pinch.begin(7, source))
        assertNull(pinch.frame(7, 0f, source))
        assertEquals(destination, pinch.frame(8, .5f, null)?.presented)
        assertTrue(pinch.discard(8))
        assertFalse(pinch.discard(8))
        assertNull(pinch.frame(8, .5f, null))
        assertFalse(pinch.begin(8, source))
        assertFalse(pinch.updateDestination(8, source))
        assertTrue(pinch.begin(9, source))
    }

    @Test fun unchangedTopOrBottomAnchorGeometryNeverAddsMovement() {
        for (rect in listOf(source, source.copy(top = 0f, bottom = 760f))) {
            val pinch = CollectionHeaderPinch()
            pinch.begin(0, rect)
            pinch.updateDestination(0, rect)
            for (p in listOf(0f, .13f, .37f, 1f, .37f, .13f, 0f)) {
                assertEquals(rect, pinch.frame(0, p, rect)?.presented)
                assertEquals(Offset(0f, 0f), pinch.frame(0, p, rect)?.liveOffset)
            }
        }
    }

    @Test fun invalidGeometryAndProgressAreRejectedRatherThanInventingCoordinates() {
        assertThrows(IllegalArgumentException::class.java) { Rect(0f, Float.NaN, 400f, 700f) }
        assertThrows(IllegalArgumentException::class.java) { Rect(0f, 0f, Float.POSITIVE_INFINITY, 700f) }
        assertThrows(IllegalArgumentException::class.java) { Rect(400f, 0f, 0f, 700f) }
        assertThrows(IllegalArgumentException::class.java) { Rect(0f, 700f, 400f, 0f) }
        val pinch = CollectionHeaderPinch()
        pinch.begin(0, source)
        assertThrows(IllegalArgumentException::class.java) { pinch.frame(0, Float.NaN, source) }
        pinch.beginHandoff(0, Endpoint.Source)
        assertThrows(IllegalArgumentException::class.java) { pinch.retireAfterHandoff(0, Float.NaN, source) }
    }
}
