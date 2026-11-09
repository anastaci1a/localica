package io.github.anastaci1a.localica.toast

import org.junit.Assert.*
import org.junit.Test

class ToastAccessibleClaimTest {
    private val command = ToastCommand.OpenQueue("queue", "occurrence", false)
    private fun ready(): Triple<ToastQueue, ToastPresenterLease, ToastEntry> {
        val queue = ToastQueue(clockMillis = { 0L })
        val lease = queue.attachPresenter()
        queue.showToast(ToastMessage("Added", tap = command, action = ToastAction("Open", command)))
        val item = queue.state.value.current!!
        lease.presented(item.id, item.generation, 3000)
        lease.marqueeCompleted(item.id, item.generation)
        lease.pause(ToastPause.Background, false)
        lease.pause(ToastPause.Accessibility, true)
        return Triple(queue, lease, item)
    }
    @Test fun focusedBodyOrActionCanClaimOnceWithoutClearingFocus() {
        for (body in listOf(false, true)) {
            val (queue, lease, item) = ready()
            assertEquals(command, lease.claimAction(item.id, item.generation, body))
            assertTrue(ToastPause.Accessibility in queue.state.value.pauses)
            assertEquals(ToastPhase.Exiting, queue.state.value.phase)
            assertNull(lease.claimAction(item.id, item.generation, !body))
        }
    }
    @Test fun focusDoesNotBypassModalBackgroundOrSettling() {
        for (pause in listOf(ToastPause.Modal, ToastPause.Background, ToastPause.Settling)) {
            val (queue, lease, item) = ready()
            lease.pause(pause, true)
            assertNull(lease.claimAction(item.id, item.generation, true))
            assertNull(lease.claimAction(item.id, item.generation, false))
            assertEquals(ToastPhase.Visible, queue.state.value.phase)
        }
    }
    @Test fun staleGenerationAndReplacedLeaseCannotClaimFocusedAction() {
        val (queue, lease, item) = ready()
        assertNull(lease.claimAction(item.id, item.generation - 1, true))
        queue.attachPresenter()
        assertNull(lease.claimAction(item.id, item.generation, true))
        assertEquals(ToastPhase.Visible, queue.state.value.phase)
    }
}
