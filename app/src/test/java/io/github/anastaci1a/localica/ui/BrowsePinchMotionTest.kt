package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.browse.BrowseStyle
import io.github.anastaci1a.localica.browse.ListSize
import kotlinx.coroutines.Job
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
