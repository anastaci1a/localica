package io.github.anastaci1a.localica.toast

import org.junit.Assert.*
import org.junit.Test

class ToastDeadlineOverflowTest {
    private fun ready(now: () -> Long, recommendation: Long): Pair<ToastQueue, ToastPresenterLease> {
        val queue = ToastQueue(clockMillis = now)
        val lease = queue.attachPresenter()
        queue.showToast(ToastMessage("Status"))
        val entry = queue.state.value.current!!
        lease.presented(entry.id, entry.generation, recommendation)
        lease.marqueeCompleted(entry.id, entry.generation)
        lease.pause(ToastPause.Background, false)
        return queue to lease
    }
    @Test fun infinityAtNonzeroClockNeverCreatesAnAlreadyExpiredDeadline() {
        val now = 1000L
        val (queue, lease) = ready({ now }, Long.MAX_VALUE)
        val ticket = queue.state.value.deadline!!
        assertTrue("No negative or already-expired deadline", ticket.atMillis > now)
        assertEquals(Long.MAX_VALUE, ticket.atMillis)
        assertEquals(Long.MAX_VALUE - now, lease.delayUntil(ticket))
        assertFalse(lease.timeout(ticket))
    }
    @Test fun largeClockFiniteDwellSaturatesAndPausePreservesRepresentableBudget() {
        var now = Long.MAX_VALUE - 20_000
        val (queue, lease) = ready({ now }, 60_000)
        val ticket = queue.state.value.deadline!!
        assertEquals(Long.MAX_VALUE, ticket.atMillis)
        assertEquals(20_000L, lease.delayUntil(ticket))
        assertFalse(lease.timeout(ticket))
        lease.pause(ToastPause.Modal, true)
        now += 10_000
        lease.pause(ToastPause.Modal, false)
        val resumed = queue.state.value.deadline!!
        assertEquals(Long.MAX_VALUE, resumed.atMillis)
        assertEquals(10_000L, lease.delayUntil(resumed))
        assertFalse(lease.timeout(ticket))
    }
}
