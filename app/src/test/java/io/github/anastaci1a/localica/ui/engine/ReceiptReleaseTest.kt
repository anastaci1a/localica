package io.github.anastaci1a.localica.ui.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #854 F-I5-2 review C3-a: the presentation receipt's listener is removed exactly once. Two queued metric callbacks and a cancel
 * all release; the platform removal (which throws when repeated) runs once, and a throwing removal never escapes. */
class ReceiptReleaseTest {
    @Test fun twoQueuedCallbacksAndACancelRemoveOnce() {
        var removals = 0
        val receipt = ReceiptRelease { removals++; if (removals > 1) throw IllegalArgumentException("never added") }
        receipt.release(); receipt.release(); receipt.release()
        assertEquals(1, removals); assertTrue(receipt.released)
    }

    @Test fun aThrowingRemovalDoesNotEscape() {
        val receipt = ReceiptRelease { throw IllegalArgumentException("never added") }
        receipt.release(); receipt.release()
        assertTrue(receipt.released)
    }
}
