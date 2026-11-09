package io.github.anastaci1a.localica.toast

import org.junit.Assert.*
import org.junit.Test

class ToastDeadlineClockTest {
    @Test fun remainingDelayUsesInjectedClockAndRejectsPausedOrRetiredLease() {
        var now = 0L
        val queue = ToastQueue(clockMillis = { now })
        val lease = queue.attachPresenter()
        queue.showToast(ToastMessage("Saved"))
        val item = queue.state.value.current!!
        lease.presented(item.id, item.generation, 3000)
        lease.marqueeCompleted(item.id, item.generation)
        lease.pause(ToastPause.Background, false)
        val ticket = queue.state.value.deadline!!
        assertEquals(3000L, lease.delayUntil(ticket))
        now = 1000
        assertEquals(2000L, lease.delayUntil(ticket))
        lease.pause(ToastPause.Modal, true)
        assertNull(lease.delayUntil(ticket))
        now = 10_000
        lease.pause(ToastPause.Modal, false)
        val resumed = queue.state.value.deadline!!
        assertEquals(2000L, lease.delayUntil(resumed))
        assertNull(lease.delayUntil(ticket))
        now = resumed.atMillis + 1
        assertEquals(0L, lease.delayUntil(resumed))
        queue.attachPresenter()
        assertNull(lease.delayUntil(resumed))
    }
}
