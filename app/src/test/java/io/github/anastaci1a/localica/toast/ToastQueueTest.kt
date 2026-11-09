package io.github.anastaci1a.localica.toast

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ToastQueueTest {
    private var now = 0L
    private fun queue(capacity: Int = 32) = ToastQueue(capacity) { now }
    private val destination = ToastCommand.OpenQueue("queue", "occurrence", false)
    private fun show(queue: ToastQueue, message: ToastMessage = ToastMessage("Saved")): ToastEntry {
        queue.showToast(message)
        return queue.state.value.current!!
    }
    private fun visible(queue: ToastQueue, marquee: Boolean = true, recommended: Long = 3_000): ToastEntry {
        val entry = queue.state.value.current!!
        queue.presented(entry.id, entry.generation, recommended)
        queue.pause(ToastPause.Background, false)
        if (marquee) queue.marqueeCompleted(entry.id, entry.generation)
        return entry
    }
    private fun keyed(command: ToastCommand = destination) = ToastMessage("Added", tap = command,
        coalescingKey = ToastKey("append", "queue", ToastKind.Info, command, null))

    @Test fun fifoRetainsSurfaceThroughExitAndRejectsOldCompletion() {
        val queue = queue()
        val first = show(queue)
        queue.showToast(ToastMessage("Second")); queue.showToast(ToastMessage("Third"))
        visible(queue)
        queue.dismiss(first.id, first.generation)
        assertEquals(first, queue.state.value.current)
        assertEquals(ToastPhase.Exiting, queue.state.value.phase)
        assertTrue(queue.exited(first.id))
        assertEquals("Second", queue.state.value.current!!.content.message)
        assertEquals(listOf("Third"), queue.state.value.pending.map { it.content.message })
        assertFalse(queue.exited(first.id))
    }

    @Test fun coalescingRetainsIdentityButFencesOldDeadlineAndMarquee() {
        val queue = queue()
        val first = show(queue, keyed())
        visible(queue)
        val ticket = queue.state.value.deadline!!
        val accepted = queue.showToast(keyed()) as ToastEnqueue.Accepted
        val updated = queue.state.value.current!!
        assertTrue(accepted.coalesced)
        assertEquals(first.id, updated.id)
        assertEquals(2, updated.count)
        assertNull(queue.state.value.deadline)
        now = ticket.atMillis
        assertFalse(queue.timeout(ticket))
        queue.marqueeCompleted(first.id, first.generation)
        assertFalse(queue.state.value.marqueeComplete)
        queue.presented(updated.id, updated.generation, 7_000)
        queue.marqueeCompleted(updated.id, updated.generation)
        assertEquals(now + 7_000, queue.state.value.deadline!!.atMillis)
    }

    @Test fun differentAddedOccurrencesNeverCoalesceIntoWrongNavigation() {
        val queue = queue()
        show(queue, keyed())
        queue.showToast(keyed(destination.copy(firstAddedEntryId = "other")))
        assertEquals(1, queue.state.value.pending.size)
        assertNotEquals(queue.state.value.current!!.content.tap, queue.state.value.pending.single().content.tap)
    }

    @Test fun pendingDuplicatesKeepFifoPosition() {
        val queue = queue()
        show(queue)
        val accepted = queue.showToast(keyed()) as ToastEnqueue.Accepted
        queue.showToast(ToastMessage("Last"))
        queue.showToast(keyed())
        assertEquals(accepted.id, queue.state.value.pending.first().id)
        assertEquals(2, queue.state.value.pending.first().count)
        assertEquals("Last", queue.state.value.pending.last().content.message)
    }

    @Test fun boundedOverflowCannotSilentlyEvictActionableEvents() {
        val queue = queue(3)
        val current = show(queue)
        queue.showToast(keyed())
        val disposable = queue.showToast(ToastMessage("Disposable")) as ToastEnqueue.Accepted
        val replacement = queue.showToast(keyed(destination.copy(queueId = "other"))) as ToastEnqueue.Accepted
        assertEquals(disposable.id, replacement.evictedId)
        assertEquals(current.id, queue.state.value.current!!.id)
        assertEquals(ToastEnqueue.Full, queue.showToast(ToastMessage("Overflow")))
        assertEquals(2, queue.state.value.pending.size)
    }

    @Test fun holdBeyondDeadlineThenCancelledReturnStartsFullAccessibleDwell() {
        val queue = queue()
        val entry = show(queue)
        visible(queue, recommended = 8_000)
        val old = queue.state.value.deadline!!
        now = 7_999
        queue.pause(ToastPause.Contact, true)
        now = 100_000
        assertFalse(queue.timeout(old))
        assertNull(queue.state.value.deadline)
        queue.pause(ToastPause.Settling, true)
        queue.pause(ToastPause.Contact, false)
        assertNull(queue.state.value.deadline)
        assertTrue(queue.retainedAfterContact(entry.id, entry.generation))
        val reset = queue.state.value.deadline!!
        assertEquals(now + 8_000, reset.atMillis)
        assertFalse(queue.timeout(old))
        now = reset.atMillis - 1
        assertFalse(queue.timeout(reset))
        now++
        assertTrue(queue.timeout(reset))
        assertFalse(queue.retainedAfterContact(entry.id, entry.generation))
    }

    @Test fun marqueeMustCompleteBeforeNormalDwellEvenAfterLongHold() {
        val queue = queue()
        val entry = show(queue, ToastMessage("Long text", kind = ToastKind.Error))
        visible(queue, marquee = false)
        now = 1_000_000
        assertNull(queue.state.value.deadline)
        queue.pause(ToastPause.Contact, true)
        queue.marqueeCompleted(entry.id, entry.generation)
        assertNull(queue.state.value.deadline)
        queue.retainedAfterContact(entry.id, entry.generation)
        assertEquals(now + 6_000, queue.state.value.deadline!!.atMillis)
    }

    @Test fun modalBackgroundAndAccessibilityPreserveUnusedDwell() {
        val queue = queue()
        show(queue); visible(queue)
        now = 1_000
        queue.pause(ToastPause.Modal, true)
        queue.pause(ToastPause.Background, true)
        queue.pause(ToastPause.Accessibility, true)
        now = 50_000
        queue.pause(ToastPause.Modal, false)
        queue.pause(ToastPause.Background, false)
        assertNull(queue.state.value.deadline)
        queue.pause(ToastPause.Accessibility, false)
        assertEquals(52_000L, queue.state.value.deadline!!.atMillis)
    }

    @Test fun activityReattachDoesNotRestartVisibleDwell() {
        val queue = queue()
        val entry = show(queue); visible(queue)
        now = 1_000
        queue.pause(ToastPause.Background, true)
        now = 10_000
        assertFalse(queue.presented(entry.id, entry.generation, 3_000))
        queue.pause(ToastPause.Background, false)
        assertEquals(12_000L, queue.state.value.deadline!!.atMillis)
    }

    @Test fun staleTimeoutCannotDismissSuccessorOrWinActionRace() {
        val queue = queue()
        val first = show(queue, keyed()); visible(queue)
        val old = queue.state.value.deadline!!
        queue.showToast(ToastMessage("Next"))
        assertEquals(destination, queue.claimAction(first.id, first.generation, body = true))
        assertNull(queue.claimAction(first.id, first.generation, body = true))
        now = old.atMillis
        assertFalse(queue.timeout(old))
        queue.exited(first.id); visible(queue)
        assertFalse(queue.timeout(old))
        assertEquals(ToastPhase.Visible, queue.state.value.phase)
    }

    @Test fun modalGatesActionsAndExitingDuplicateGetsNewIdentity() {
        val queue = queue()
        val first = show(queue, keyed()); visible(queue)
        queue.pause(ToastPause.Modal, true)
        assertNull(queue.claimAction(first.id, first.generation, body = true))
        queue.pause(ToastPause.Modal, false)
        assertEquals(destination, queue.claimAction(first.id, first.generation, body = true))
        val later = queue.showToast(keyed()) as ToastEnqueue.Accepted
        assertFalse(later.coalesced)
        assertNotEquals(first.id, later.id)
    }

    @Test fun competingActionClaimsDispatchOnlyOnce() {
        val queue = queue()
        val command = ToastCommand.Operation("tag-jobs", "retry-123", 7)
        val entry = show(queue, ToastMessage("Failed", action = ToastAction("Retry", command), tap = destination))
        visible(queue)
        val pool = Executors.newFixedThreadPool(4)
        val ready = CountDownLatch(1)
        try {
            val claims = (0 until 16).map { index -> pool.submit<ToastCommand?> {
                ready.await(); queue.claimAction(entry.id, entry.generation, body = index % 2 == 0)
            } }
            ready.countDown()
            assertEquals(1, claims.mapNotNull { it.get(5, TimeUnit.SECONDS) }.size)
        } finally { pool.shutdownNow() }
    }

    @Test fun snapshotsDefendAgainstMutableArtworkAndRequireValidDurationAndKey() {
        val covers = mutableListOf("a", "b", "c", "d")
        val queue = queue()
        show(queue, ToastMessage("Covers", artworkUris = covers))
        covers.clear()
        assertEquals(listOf("a", "b", "c"), queue.state.value.current!!.content.artworkUris)
        assertThrows(IllegalArgumentException::class.java) { ToastMessage("Bad", durationMillis = 0) }
        assertThrows(IllegalArgumentException::class.java) {
            keyed().copy(tap = destination.copy(queueId = "different"))
        }
    }
}
