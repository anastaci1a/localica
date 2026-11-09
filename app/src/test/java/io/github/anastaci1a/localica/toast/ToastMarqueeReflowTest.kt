package io.github.anastaci1a.localica.toast

import org.junit.Assert.*
import org.junit.Test

class ToastMarqueeReflowTest {
    @Test fun reflowInvalidatesPriorTicketAndPreservesUnspentDwell() {
        var now = 0L
        val queue = ToastQueue(clockMillis = { now })
        val lease = queue.attachPresenter()
        queue.showToast(ToastMessage("An outcome"))
        val item = queue.state.value.current!!
        lease.presented(item.id, item.generation, 3000)
        lease.marqueeCompleted(item.id, item.generation)
        lease.pause(ToastPause.Background, false)
        val old = queue.state.value.deadline!!
        now = 1000
        assertTrue(lease.marqueePending(item.id, item.generation))
        assertNull(queue.state.value.deadline)
        now = 5000
        assertFalse(lease.timeout(old))
        lease.marqueeCompleted(item.id, item.generation)
        assertEquals(7000L, queue.state.value.deadline!!.atMillis)
    }

    @Test fun retiredPresenterCannotRestartReadingOrRevokeReplacementDeadline() {
        val queue = ToastQueue(clockMillis = { 0L })
        val old = queue.attachPresenter()
        queue.showToast(ToastMessage("An outcome"))
        val item = queue.state.value.current!!
        val current = queue.attachPresenter()
        current.presented(item.id, item.generation, 3000)
        current.marqueeCompleted(item.id, item.generation)
        current.pause(ToastPause.Background, false)
        val ticket = queue.state.value.deadline
        assertFalse(old.marqueePending(item.id, item.generation))
        assertEquals(ticket, queue.state.value.deadline)
    }
}
