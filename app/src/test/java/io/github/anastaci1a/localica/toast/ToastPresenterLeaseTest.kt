package io.github.anastaci1a.localica.toast

import org.junit.Assert.*
import org.junit.Test

class ToastPresenterLeaseTest {
    private var now = 0L
    private fun queue() = ToastQueue(clockMillis = { now })
    private fun shown(queue: ToastQueue, lease: ToastPresenterLease): ToastEntry {
        val entry = queue.state.value.current!!
        lease.pause(ToastPause.Background, false)
        lease.presented(entry.id, entry.generation, 3000)
        lease.marqueeCompleted(entry.id, entry.generation)
        return entry
    }

    @Test fun replacementRejectsEveryOldHostMutationAndRetainsTheDwellRemainder() {
        val queue = queue()
        queue.showToast(ToastMessage("Saved", tap = ToastCommand.OpenList("list", "occurrence")))
        val first = queue.attachPresenter()
        val entry = shown(queue, first)
        val oldTicket = queue.state.value.deadline!!
        now = 500
        val second = queue.attachPresenter()
        assertFalse(first.current)
        assertTrue(second.current)
        assertFalse(queue.state.value.marqueeComplete)
        first.pause(ToastPause.Background, false)
        first.pause(ToastPause.Modal, true)
        assertFalse(first.dismiss(entry.id, entry.generation))
        assertFalse(first.presented(entry.id, entry.generation, 9000))
        assertNull(first.claimAction(entry.id, entry.generation, body = true))
        assertFalse(first.exited(entry.id))
        assertFalse(first.timeout(oldTicket))
        assertTrue(ToastPause.Background in queue.state.value.pauses)
        assertFalse(ToastPause.Modal in queue.state.value.pauses)
        second.pause(ToastPause.Settling, true)
        second.pause(ToastPause.Background, false)
        second.marqueeCompleted(entry.id, entry.generation)
        assertNull(queue.state.value.deadline)
        now = 600
        second.pause(ToastPause.Settling, false)
        assertEquals(3100L, queue.state.value.deadline!!.atMillis)
    }

    @Test fun staleDisposalCannotBackgroundTheReplacementButCurrentDisposalCan() {
        val queue = queue()
        queue.showToast(ToastMessage("Saved"))
        val first = queue.attachPresenter()
        val second = queue.attachPresenter()
        shown(queue, second)
        val before = queue.state.value
        assertFalse(first.release())
        assertEquals(before, queue.state.value)
        assertTrue(second.release())
        assertNull(queue.activePresenter.value)
        assertTrue(ToastPause.Background in queue.state.value.pauses)
        assertNull(queue.state.value.deadline)
        assertFalse(second.release())
    }

    @Test fun oldExitCompletionCannotRetireTheReplacementOrItsSuccessor() {
        val queue = queue()
        queue.showToast(ToastMessage("First"))
        queue.showToast(ToastMessage("Second"))
        val first = queue.attachPresenter()
        val entry = shown(queue, first)
        assertTrue(first.dismiss(entry.id, entry.generation))
        val second = queue.attachPresenter()
        assertFalse(first.exited(entry.id))
        assertEquals(entry.id, queue.state.value.current!!.id)
        assertTrue(second.exited(entry.id))
        assertEquals("Second", queue.state.value.current!!.content.message)
        assertFalse(first.exited(entry.id))
        assertFalse(second.exited(entry.id))
    }

    @Test fun aRecreatedHeldEntryResetsItsFullDwellOnlyAfterTheNewOwnerSettles() {
        val queue = queue()
        queue.showToast(ToastMessage("Held"))
        val first = queue.attachPresenter()
        val entry = shown(queue, first)
        now = 1000
        first.pause(ToastPause.Contact, true)
        now = 20_000
        val second = queue.attachPresenter()
        second.pause(ToastPause.Background, false)
        second.marqueeCompleted(entry.id, entry.generation)
        assertNull(queue.state.value.deadline)
        assertFalse(first.retainedAfterContact(entry.id, entry.generation))
        assertTrue(second.retainedAfterContact(entry.id, entry.generation))
        assertEquals(23_000L, queue.state.value.deadline!!.atMillis)
    }
}
