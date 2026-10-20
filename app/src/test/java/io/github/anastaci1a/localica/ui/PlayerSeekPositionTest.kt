package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.playback.SeekAcknowledgement
import org.junit.Assert.*
import org.junit.Test

class PlayerSeekPositionTest {
    private val live = PlaybackState(positionMs = 20_000, durationMs = 120_000,
        playbackVisit = "visit-a", seekAcknowledgement = receipt(4, 20_000))
    private fun receipt(request: Long, position: Long, visit: String = "visit-a", applied: Boolean = true) =
        SeekAcknowledgement(request, position, visit, applied)

    @Test fun releaseRetainsFinalRatherThanLiveOrDragStartUntilMatchingAcknowledgement() {
        val position = PlayerSeekPosition()
        val seeks = mutableListOf<Long>()
        position.edit(50_000f) // Finger-down position, distinct from live playback.
        position.edit(95_000f)
        position.finish(live) { seeks += it; 5 }
        assertEquals(listOf(95_000L), seeks)
        assertEquals(95_000f, position.displayed(live))
        for (sample in listOf(20_250L, 50_000L, 94_999L, 95_001L)) {
            assertEquals("A fresh poll at $sample is not a seek acknowledgement", 95_000f,
                position.displayed(live.copy(positionMs = sample, sampledAtMs = sample)))
        }
        val acknowledged = live.copy(positionMs = 95_030, seekAcknowledgement = receipt(5, 95_000))
        assertEquals(95_030f, position.displayed(acknowledged))
        position.observe(acknowledged.seekAcknowledgement)
        assertNull(position.pending)
        assertEquals(95_280f, position.displayed(acknowledged.copy(positionMs = 95_280)))
    }

    @Test fun otherRequestSameTargetOrReceiptForOtherVisitCannotAcknowledgeRelease() {
        val position = PlayerSeekPosition()
        position.edit(95_000f)
        position.finish(live) { 5 }
        for (ack in listOf(receipt(4, 95_000), receipt(6, 95_000), receipt(5, 95_000, "visit-b"))) {
            val sample = live.copy(positionMs = 50_000, seekAcknowledgement = ack)
            position.observe(ack)
            assertEquals(95_000f, position.displayed(sample))
            assertNotNull(position.pending)
        }
    }

    @Test fun rapidOppositeAndRepeatedDestinationSeeksRejectEveryOlderReceipt() {
        val position = PlayerSeekPosition()
        position.edit(95_000f); position.finish(live) { 5 }
        val first = position.pending!!
        position.edit(10_000f); position.finish(live) { 6 }
        position.edit(95_000f); position.finish(live) { 7 }
        position.expire(first)
        for (ack in listOf(receipt(5, 95_000), receipt(6, 10_000))) {
            position.observe(ack)
            assertNotNull("ABA cannot release the latest request", position.pending)
            assertEquals(95_000f, position.displayed(live.copy(positionMs = ack.positionMs, seekAcknowledgement = ack)))
        }
        position.observe(receipt(7, 95_000))
        assertNull(position.pending)
    }

    @Test fun repeatedSameTargetHasIndependentWatchdogIdentity() {
        val position = PlayerSeekPosition()
        position.edit(95_000f); position.finish(live) { 5 }
        val first = position.pending!!
        position.edit(95_000f); position.finish(live) { 6 }
        val second = position.pending!!
        assertNotSame(first, second)
        position.expire(first)
        assertSame(second, position.pending)
        position.expire(second)
        assertNull(position.pending)
        assertEquals(20_000f, position.displayed(live))
    }

    @Test fun cancelledOrUnchangedContactDoesNotSeekAndReturnsToLivePlayback() {
        val position = PlayerSeekPosition()
        val seeks = mutableListOf<Long>()
        fun seek(value: Long): Long { seeks += value; return 5 }
        position.finish(live, ::seek)
        assertTrue(seeks.isEmpty())
        position.edit(50_000f); position.edit(95_000f)
        position.cancel(); position.finish(live, ::seek)
        assertTrue(seeks.isEmpty())
        assertNull(position.pending)
        assertEquals(20_000f, position.displayed(live))
    }

    @Test fun cancellingNewContactPreservesEarlierCommittedButUnacknowledgedSeek() {
        val position = PlayerSeekPosition()
        position.edit(95_000f); position.finish(live) { 5 }
        position.edit(40_000f)
        position.cancel()
        assertEquals(95_000f, position.displayed(live))
        assertEquals(5L, position.pending!!.requestId)
        position.observe(receipt(5, 95_000))
        assertNull(position.pending)
    }

    @Test fun adjustedOrRejectedNativeResultReleasesImmediatelyWithoutTargetEquality() {
        for (applied in listOf(true, false)) {
            val position = PlayerSeekPosition()
            position.edit(95_000f); position.finish(live) { 5 }
            val acceptedPosition = if (applied) 94_500L else 20_000L
            val result = live.copy(positionMs = acceptedPosition,
                seekAcknowledgement = receipt(5, acceptedPosition, applied = applied))
            assertEquals(acceptedPosition.toFloat(), position.displayed(result))
            position.observe(result.seekAcknowledgement)
            assertNull(position.pending)
        }
    }

    @Test fun legacyCallbackDoesNotWaitForAnUnavailableReceiptAndThrowingCallbackClearsEditing() {
        val position = PlayerSeekPosition()
        position.edit(95_000f)
        position.finish(live) { null }
        assertNull(position.pending)
        assertEquals(20_000f, position.displayed(live))
        position.edit(95_000f); position.finish(live) { 5 }
        position.edit(40_000f)
        try { position.finish(live) { error("Rejected dispatch") }; fail("Expected rejection") }
        catch (_: IllegalStateException) { }
        assertNull(position.editing)
        assertEquals("A throwing new request cannot revoke the prior commitment", 95_000f, position.displayed(live))
    }

    @Test fun synchronousAcknowledgementHasNoRequiredHoldAndLateReceiptDoesNotOverrideNewDrag() {
        val position = PlayerSeekPosition()
        var playback = live
        position.edit(95_000f)
        position.finish(playback) { playback = live.copy(positionMs = it, seekAcknowledgement = receipt(5, it)); 5 }
        assertEquals(95_000f, position.displayed(playback))
        position.edit(40_000f)
        position.observe(playback.seekAcknowledgement)
        assertEquals(40_000f, position.displayed(playback))
        position.cancel()
        assertEquals(95_000f, position.displayed(playback))
    }
}
