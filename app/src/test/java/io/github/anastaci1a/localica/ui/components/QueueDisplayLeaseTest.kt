package io.github.anastaci1a.localica.ui.components

import org.junit.Assert.*
import org.junit.Test

class QueueDisplayLeaseTest {
    @Test fun staleBrowseCannotAdmitAfterDismissalRouteReplacementOrDisposal() {
        val lease = QueueDisplayLease()
        val cancelled = lease.request("inactive", "route")!!
        lease.invalidate()
        assertFalse(lease.admit(cancelled, "route", true, true))
        val replaced = lease.request("inactive", "route")!!
        assertFalse(lease.admit(replaced, "other-route", true, true))
        val disposed = lease.request("inactive", "route")!!
        lease.dispose()
        assertFalse(lease.admit(disposed, "route", true, true))
        assertNull(lease.request("inactive", "route"))
        assertNull(lease.queueId)
    }

    @Test fun foreignAndRepeatedCallbacksAndMissingDestinationsCannotReopen() {
        val lease = QueueDisplayLease()
        val other = QueueDisplayLease()
        assertFalse(lease.admit(other.request("foreign", "route")!!, "route", true, true))
        assertFalse(lease.admit(lease.request("missing", "route")!!, "route", false, true))
        assertFalse(lease.admit(lease.request("retired-presenter", "route")!!, "route", true, false))
        val accepted = lease.request("inactive", "route")!!
        assertTrue(lease.admit(accepted, "route", true, true))
        lease.reconcile(false, false, false, true, true)
        assertNull(lease.queueId)
        assertFalse(lease.admit(accepted, "route", true, true))
    }

    @Test fun collapseAndCaughtReversalRetainDisplayUntilBothRealEndpoints() {
        val lease = QueueDisplayLease()
        assertTrue(lease.admit(lease.request("inactive", "route")!!, "route", true, true))
        lease.invalidate()
        lease.reconcile(false, false, false, false, false)
        assertEquals("inactive", lease.queueId)
        lease.reconcile(false, false, false, true, false)
        assertEquals("inactive", lease.queueId)
        lease.reconcile(false, true, true, true, true)
        assertEquals("inactive", lease.queueId)
        // Root's endpoint predicate stays false during a contact, even at phase zero.
        lease.reconcile(false, false, false, false, true)
        assertEquals("inactive", lease.queueId)
        lease.reconcile(false, false, false, true, true)
        assertNull(lease.queueId)
    }

    @Test fun playbackTakesOverTheSameDisplayAdmissionAndRestorationRetiresOldCallbacks() {
        val previous = QueueDisplayLease()
        val pending = previous.request("inactive", "route")!!
        assertTrue(previous.admit(pending, "route", true, true))
        val restored = QueueDisplayLease(previous.queueId)
        previous.dispose()
        assertFalse(restored.admit(pending, "route", true, true))
        assertEquals("inactive", restored.queueId)
        restored.reconcile(true, true, true, false, false)
        assertNull(restored.queueId)
    }
    @Test fun restoredLeaseSurvivesLoadingEmptyThenAuthoritativeExistingSnapshot() {
        val lease = QueueDisplayLease("inactive")
        val pending = lease.request("inactive", "route")!!
        assertFalse(lease.destinationMissing(loading = true, exists = false))
        assertEquals("inactive", lease.queueId)
        assertFalse(lease.destinationMissing(loading = false, exists = true))
        assertTrue(lease.admit(pending, "route", true, true))
        assertEquals("inactive", lease.queueId)
    }

    @Test fun restoredLeaseRequestsCollapseOnlyAfterLoadedDeletionAndRetainsThroughIt() {
        val lease = QueueDisplayLease("deleted")
        val pending = lease.request("deleted", "route")!!
        assertFalse(lease.destinationMissing(loading = true, exists = false))
        assertTrue(lease.destinationMissing(loading = false, exists = false))
        assertFalse(lease.admit(pending, "route", true, true))
        lease.reconcile(false, false, false, false, true)
        assertEquals("deleted", lease.queueId)
        lease.reconcile(false, false, false, true, true)
        assertNull(lease.queueId)
    }

}
