package io.github.anastaci1a.localica.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ListReturnActionTest {
    @Test fun admissionIsPendingBeforeReadinessAndOldCompletionCannotClearItsReplacement() = runTest {
        var pending = false
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        val executor = ListReturnExecutor(this) { pending = it }
        val command = ListReturnCommand({
            executor.submit({ true }, { first.await() }, { it }, {})
        }, executor::cancel) { pending }
        command(ListReturnAction.ScrollTop)
        assertTrue("Admission hides the control before the coroutine is scheduled", command.pending)
        runCurrent()
        executor.submit({ true }, { second.await() }, { ListReturnAction.ScrollTop }, {})
        first.complete(Unit)
        runCurrent()
        assertTrue("Retired completion cannot expose a pending replacement", command.pending)
        second.complete(Unit)
        runCurrent()
        assertFalse(command.pending)
        command(ListReturnAction.ScrollTop)
        command.cancel()
        assertFalse("Physical cancellation releases the pending owner synchronously", command.pending)
        runCurrent()
        assertFalse(command.pending)
    }

    @Test fun scrollThenFilterThenOrdinaryBackHasOneApplicableStep() {
        assertEquals(ListReturnAction.ScrollTop, listReturnAction(true, false, true))
        assertEquals(ListReturnAction.ScrollTop, listReturnAction(false, true, true))
        assertEquals(ListReturnAction.ClearFilter, listReturnAction(false, false, true))
        assertEquals(ListReturnAction.None, listReturnAction(false, false, false))
    }

    @Test fun aReadyResultCannotTransferAnOldPressToANewOwner() = runTest {
        val ready = CompletableDeferred<Unit>()
        var ownsList = true
        val actions = mutableListOf<ListReturnAction>()
        val executor = ListReturnExecutor(this)
        executor.submit({ ownsList }, { ready.await() }, { ListReturnAction.ClearFilter }, { actions += it })
        runCurrent()
        ownsList = false
        ready.complete(Unit)
        runCurrent()
        assertTrue(actions.isEmpty())
    }

    @Test fun capturedBackStepDoesNotBecomeFilterClearWhenReadinessChanges() = runTest {
        val ready = CompletableDeferred<Unit>()
        var scrolled = true
        val captured = listReturnAction(scrolled, false, true)
        val actions = mutableListOf<ListReturnAction>()
        val executor = ListReturnExecutor(this)
        executor.submit({ true }, { ready.await() }, { captured }, { actions += it })
        runCurrent()
        scrolled = false
        ready.complete(Unit)
        runCurrent()
        assertEquals(ListReturnAction.ClearFilter, listReturnAction(scrolled, false, true))
        assertEquals(listOf(ListReturnAction.ScrollTop), actions)
    }

    @Test fun newCommandCancelsOldMotionAndDisposalCancelsPendingReadiness() = runTest {
        val events = mutableListOf<String>()
        val executor = ListReturnExecutor(this)
        executor.submit({ true }, {}, { ListReturnAction.ScrollTop }, {
            events += "first started"
            try { awaitCancellation() } finally { events += "first canceled" }
        })
        runCurrent()
        executor.submit({ true }, {}, { ListReturnAction.ScrollTop }, { events += "replacement" })
        runCurrent()
        assertEquals(listOf("first started", "first canceled", "replacement"), events)
        val ready = CompletableDeferred<Unit>()
        executor.submit({ true }, { ready.await() }, { ListReturnAction.ClearFilter }, { events += "stale filter" })
        runCurrent()
        executor.cancel()
        ready.complete(Unit)
        runCurrent()
        assertFalse("stale filter" in events)
    }
}
