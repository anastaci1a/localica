package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import io.github.anastaci1a.localica.browse.BrowseStyle
import io.github.anastaci1a.localica.browse.ListSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BrowsePinchMotionTest {
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
}
