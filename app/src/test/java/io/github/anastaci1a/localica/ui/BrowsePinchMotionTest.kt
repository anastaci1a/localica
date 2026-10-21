package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import io.github.anastaci1a.localica.browse.BrowseStyle
import io.github.anastaci1a.localica.browse.ListSize
import io.github.anastaci1a.localica.browse.BrowseCellGeometry
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BrowsePinchMotionTest {
    @Test fun smallestPaddingKeepsLiveAndRetainedInnerOriginsContinuousInBothDirections() {
        for ((sourceSize, targetSize) in listOf(ListSize.Small to ListSize.Smallest, ListSize.Smallest to ListSize.Small)) {
            for (density in listOf(1f, 1.75f, 2.75f)) {
                val geometry = BrowseCellGeometry(1)
                val sourceInset = (geometry.cardVerticalInset(BrowseStyle(listSize = sourceSize)) * density).roundToInt()
                val targetInset = (geometry.cardVerticalInset(BrowseStyle(listSize = targetSize)) * density).roundToInt()
                val sourceTop = 20f; val targetTop = 70f
                for (p in listOf(0f, .1f, .5f, 1f, .6f, .2f, 0f)) {
                    val actual = targetTop + targetInset + pinchContentTranslation(sourceTop, targetTop, sourceInset, targetInset, p)
                    val expected = (sourceTop + sourceInset) * (1f - p) + (targetTop + targetInset) * p
                    assertEquals("$sourceSize to $targetSize at $density/$p", expected, actual, .0001f)
                }
                assertEquals(0f, pinchContentTranslation(sourceTop, targetTop, sourceInset, targetInset, 1f), 0f)
            }
        }
    }

    @Test fun sourceLayerTreeDoesNotChangeUntilReadbackFinishes() {
        val original = BrowseStyle(listSize = ListSize.Large)
        val motion = BrowsePinchMotion().apply {
            capturing = true
            source = original
            target = BrowseStyle(listSize = ListSize.Normal)
        }
        assertEquals("Capture already owns the gesture", original, motion.source)
        assertNull("Nested artwork layers must survive the asynchronous source readback", motion.visualSource)
        val size = Size(120f, 160f)
        assertEquals(size, motion.highlightSize("song", 0, size))
        motion.capturing = false
        assertEquals("Only the finished snapshot permits the live tree to reflow", original, motion.visualSource)
        motion.clearTransaction()
        assertNull(motion.visualSource)
    }

    @Test fun bitmapLeaseEndsExactlyOnceOnHandoffFailureOrDisposal() {
        for (finish in listOf<(BrowsePinchMotion) -> Unit>({ it.clearTransaction() }, { it.failCapture() }, { it.dispose() })) {
            var released = 0
            val motion = BrowsePinchMotion().apply {
                source = BrowseStyle(); held = true
                releaseArtwork = { released++ }
            }
            finish(motion)
            motion.clearTransaction()
            assertEquals(1, released)
            assertNull(motion.releaseArtwork)
        }
    }

    @Test fun modalRollbackRetiresGenerationButKeepsArtworkThroughTwoFrames() = runBlocking {
        val clock = androidx.compose.runtime.BroadcastFrameClock()
        val scope = kotlinx.coroutines.CoroutineScope(coroutineContext + clock)
        var released = 0
        val motion = BrowsePinchMotion().apply {
            source = BrowseStyle(); releaseArtwork = { released++ }
        }
        val generation = motion.transaction
        motion.releaseArtworkAfterHandoff(scope)
        motion.clearTransaction()
        assertFalse(motion.owns(generation))
        assertEquals(0, released)
        clock.sendFrame(1L); kotlinx.coroutines.yield()
        assertEquals("Rollback remount still needs the source bitmap on frame one", 0, released)
        clock.sendFrame(2L); kotlinx.coroutines.yield()
        assertEquals(1, released)
        motion.clearTransaction()
        assertEquals(1, released)
    }

    @Test fun cancelledRollbackScopeStillReleasesTheDetachedLease() = runBlocking {
        val owner = Job()
        val clock = androidx.compose.runtime.BroadcastFrameClock()
        val scope = kotlinx.coroutines.CoroutineScope(owner + clock)
        var released = 0
        val motion = BrowsePinchMotion().apply { releaseArtwork = { released++ } }
        motion.releaseArtworkAfterHandoff(scope)
        owner.cancel(); owner.join()
        assertEquals(1, released)
    }

    @Test fun handoffClearsBothDestinationsAndAnchorState() {
        val motion = BrowsePinchMotion().apply {
            source = BrowseStyle(listSize = ListSize.Compact)
            target = BrowseStyle(listSize = ListSize.Normal)
            held = true; capturing = true; fingerProgress = .8f
            focusKey = "song"; focusTop = 80; focusFraction = .5f; focusEdge = 1
            bounds = mapOf("song" to Rect(0f, 0f, 50f, 50f))
        }
        motion.clearTransaction()
        assertNull(motion.source); assertNull(motion.target); assertNull(motion.focusKey)
        assertFalse(motion.held); assertFalse(motion.capturing)
        assertEquals(0f, motion.fingerProgress, 0f)
        assertEquals(0, motion.focusTop); assertEquals(0f, motion.focusFraction, 0f); assertEquals(0, motion.focusEdge)
        assertTrue(motion.bounds.isEmpty()); assertTrue(motion.frozen.isEmpty())
    }

    @Test fun highlightFollowsInterpolatedContentBoundsInBothDirectionsAndReversal() = runBlocking {
        for ((sourceHeight, targetHeight) in listOf(96f to 162f, 162f to 96f)) {
            val midpoint = 441f
            val inset = 8f // 4 dp card padding at density 2, excluded from the slab.
            val targetSize = Size(600f, targetHeight)
            val motion = BrowsePinchMotion().apply {
                source = BrowseStyle()
                sourceColumns = 1
                pixelDensity = 2f
                bounds = mapOf("song" to Rect(0f, midpoint - sourceHeight / 2f - inset,
                    300f, midpoint + sourceHeight / 2f + inset))
            }
            for (p in listOf(0f, .3f, .7f, 1f, .7f, .3f, 0f)) {
                motion.progress.snapTo(p)
                val visual = motion.highlightSize("song", 0, targetSize)
                val height = visual.height
                assertEquals(284f + (targetSize.width - 284f) * p, visual.width, .001f)
                assertEquals(sourceHeight + (targetHeight - sourceHeight) * p, height, .001f)
                val top = (midpoint - sourceHeight / 2f) * (1f - p) +
                    (midpoint - targetHeight / 2f) * p
                assertEquals("The visual center must stay anchored while measured height stays final",
                    midpoint, top + height / 2f, .001f)
            }
            motion.clearTransaction()
            assertEquals("Ordinary highlights keep their measured size", targetSize,
                motion.highlightSize("song", 0, targetSize))
        }
    }

    @Test fun disposalCancelsEveryTransactionJobBeforeClearingOwnership() {
        val capture = Job(); val tracking = Job(); val settling = Job()
        val motion = BrowsePinchMotion().apply {
            this.capture = capture; this.tracking = tracking; this.settling = settling
            source = BrowseStyle(); target = BrowseStyle(listSize = ListSize.Large)
        }
        motion.dispose()
        assertTrue(motion.disposed)
        assertTrue(capture.isCancelled); assertTrue(tracking.isCancelled); assertTrue(settling.isCancelled)
        assertNull(motion.source); assertNull(motion.target)
        assertNull(motion.capture); assertNull(motion.tracking); assertNull(motion.settling)
    }

    @Test fun failedReadbackRetiresHeldGestureWithoutPublishingItsTarget() {
        val motion = BrowsePinchMotion().apply {
            source = BrowseStyle(listSize = ListSize.Compact)
            target = BrowseStyle(listSize = ListSize.Normal)
            held = true; capturing = true
        }
        motion.failCapture()
        assertNull(motion.source); assertNull(motion.target)
        assertFalse(motion.held); assertFalse(motion.capturing)
        assertFalse(motion.captureProblem)
    }

    @Test fun failedReadbackDuringReleaseBlocksCommitUntilReleaseCleanup() {
        val original = BrowseStyle(listSize = ListSize.Compact)
        val motion = BrowsePinchMotion().apply {
            source = original
            target = BrowseStyle(listSize = ListSize.Normal)
            held = false; capturing = true
        }
        motion.failCapture()
        assertTrue("The waiting release must abort rather than commit its target", motion.captureProblem)
        assertEquals("Release retains ownership until its finally block", original, motion.source)
        motion.clearTransaction()
        assertNull(motion.source); assertNull(motion.target)
        assertFalse(motion.captureProblem)
    }

    @Test fun missingSnapshotTimesOutAndClosesTheCapture() = runBlocking {
        var closed = false
        val result = awaitPinchSnapshot<Unit>(20) {
            try { kotlinx.coroutines.awaitCancellation() } finally { closed = true }
        }
        assertNull(result)
        assertTrue(closed)
    }

    @Test fun cancellingSnapshotPropagatesAndClosesTheCapture() = runBlocking {
        var closed = false
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val pending = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            awaitPinchSnapshot<Unit> {
                try { entered.complete(Unit); kotlinx.coroutines.awaitCancellation() }
                finally { closed = true }
            }
        }
        entered.await()
        pending.cancel()
        pending.join()
        assertTrue(pending.isCancelled)
        assertTrue(closed)
    }

    @Test fun aRetiredGenerationCannotPublishIntoOrClearANewerPinch() {
        val motion = BrowsePinchMotion().apply {
            source = BrowseStyle(listSize = ListSize.Compact)
            target = BrowseStyle(listSize = ListSize.Normal)
        }
        val retired = motion.transaction
        assertTrue(motion.owns(retired))
        motion.clearTransaction()
        motion.source = BrowseStyle(listSize = ListSize.Small)
        motion.target = BrowseStyle(listSize = ListSize.Compact)
        val current = motion.transaction

        assertNotEquals(retired, current)
        assertFalse("A late capture result must not publish its obsolete destination", motion.owns(retired))
        // The production capture/settlement finally blocks use the same ownership guard.
        if (motion.owns(retired)) motion.clearTransaction()
        assertTrue(motion.owns(current))
        assertEquals(ListSize.Small, motion.source?.listSize)
        assertEquals(ListSize.Compact, motion.target?.listSize)
    }

    @Test fun committedHandoffStateDoesNotLeakIntoTheNextTransaction() {
        val motion = BrowsePinchMotion().apply {
            source = BrowseStyle(listSize = ListSize.Compact)
            target = BrowseStyle(listSize = ListSize.Normal)
            committed = true
        }
        val committedGeneration = motion.transaction
        motion.clearTransaction()
        assertFalse(motion.committed)
        assertFalse(motion.owns(committedGeneration))

        motion.source = BrowseStyle(listSize = ListSize.Normal)
        motion.target = BrowseStyle(listSize = ListSize.Large)
        assertFalse("A new preview must remain eligible for cancellation rollback", motion.committed)
        assertTrue(motion.owns(motion.transaction))
    }

}
