package io.github.anastaci1a.localica.ui

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BackdropReadbackTest {
    private class Fixture {
        data class Task(val delay: Long, val action: () -> Unit, var cancelled: Boolean = false)
        val tasks = mutableListOf<Task>()
        var scene = BackdropReadbackScene("viewport", mapOf(
            "lyrics" to BackdropReadbackProducer("line30", true),
            "marker" to BackdropReadbackProducer("frame9", false)))
        var redraws = 0
        var sceneReads = 0
        val barrier = BackdropReadback({ sceneReads++; scene }) { delay, action ->
            val task = Task(delay, action)
            tasks.add(task)
            val cancel: () -> Unit = { task.cancelled = true }
            cancel
        }.apply { invalidateComposite = { redraws++ } }
        fun draws(request: Int) = scene.producers.mapValues { (_, producer) ->
            BackdropProducerDraws(request, request.takeIf { producer.masked })
        }
        fun producers(request: Int) {
            barrier.producerRecorded("lyrics", request)
            barrier.producerMasked("lyrics", request)
            barrier.producerRecorded("marker", request)
        }
        fun ready(request: Int) { producers(request); barrier.compositeRecorded(request, draws(request)) }
        fun run(delay: Long, evenCancelled: Boolean = false) {
            val selected = tasks.filter { it.delay == delay }
            tasks.removeAll(selected.toSet())
            selected.filter { evenCancelled || !it.cancelled }.forEach { it.action() }
        }
    }

    @Test fun idleAndCompletedCallbacksDoNotSnapshotProducerState() = runTest {
        val f = Fixture()
        assertFalse(f.barrier.isPending)
        f.producers(0)
        f.barrier.compositeRecorded(0, emptyMap())
        assertEquals(0, f.sceneReads)
        val ticket = f.barrier.begin(9)
        assertTrue(f.barrier.isPending)
        f.ready(9)
        f.run(0)
        assertFalse(f.barrier.isPending)
        val reads = f.sceneReads
        f.producers(9)
        f.barrier.compositeRecorded(9, emptyMap())
        assertEquals(reads, f.sceneReads)
        ticket.close()
    }

    @Test fun everyOuterAndMaskMustMatchBeforeTheCompositeCanComplete() = runTest {
        val f = Fixture()
        val ticket = f.barrier.begin(9)
        val result = async(start = CoroutineStart.UNDISPATCHED) { ticket.awaitDraws() }
        f.barrier.producerRecorded("lyrics", 8)
        f.barrier.producerMasked("lyrics", 9)
        f.barrier.producerMasked("marker", 9) // A mask cannot attest an unmasked outer recording.
        f.barrier.compositeRecorded(9, f.draws(9))
        f.run(0)
        assertFalse(result.isCompleted)
        assertEquals(0, f.redraws)
        f.producers(9)
        f.barrier.compositeRecorded(8, f.draws(8))
        f.run(0)
        assertEquals(1, f.redraws)
        assertFalse(result.isCompleted)
        f.barrier.compositeRecorded(9, f.draws(9))
        assertFalse(result.isCompleted) // Never resume readback within record's draw stack.
        f.run(0)
        assertEquals(BackdropReadbackDraws(9, f.draws(9), 9), result.await())
        ticket.close()
    }

    @Test fun aggregateBeforeANewChildNeedsOneFreshAggregateRecord() = runTest {
        val f = Fixture()
        val ticket = f.barrier.begin(9)
        f.barrier.compositeRecorded(9, emptyMap())
        f.producers(9)
        f.producers(9)
        assertEquals(0, f.redraws) // Must leave the producer draw stack before invalidation.
        val result = async(start = CoroutineStart.UNDISPATCHED) { ticket.awaitDraws() }
        f.run(0)
        assertEquals(1, f.redraws)
        assertFalse(result.isCompleted)
        f.barrier.compositeRecorded(9, f.draws(9))
        f.run(0)
        assertNotNull(result.await())
        ticket.close()
    }

    @Test fun lateMasksJoinEarlierOuterRecordsWithoutAnotherOuterDraw() = runTest {
        val f = Fixture()
        f.scene = f.scene.copy(producers = f.scene.producers +
            ("incoming" to BackdropReadbackProducer("line30 incoming half", true)))
        val ticket = f.barrier.begin(9)
        val result = async(start = CoroutineStart.UNDISPATCHED) { ticket.awaitDraws() }
        f.barrier.producerMasked("lyrics", 8)
        f.barrier.producerRecorded("lyrics", 9) // Outer redraw can reuse a clean clipping RenderNode.
        f.barrier.producerMasked("incoming", 9) // The other retained document finishes in reverse order.
        f.barrier.producerRecorded("marker", 9)
        f.barrier.compositeRecorded(9, f.draws(9))
        f.run(0)
        assertFalse(result.isCompleted)
        assertEquals(0, f.redraws)
        f.barrier.producerRecorded("incoming", 9)
        f.barrier.producerMasked("lyrics", 9) // No second lyrics outer callback follows this mask.
        f.barrier.producerMasked("lyrics", 8) // A late old callback cannot erase matching evidence.
        f.barrier.producerMasked("lyrics", 9)
        assertEquals(0, f.redraws)
        f.run(0)
        assertEquals(1, f.redraws)
        assertFalse(result.isCompleted)
        f.barrier.compositeRecorded(9, f.draws(9))
        f.run(0)
        assertEquals(BackdropReadbackDraws(9, f.draws(9), 9), result.await())
        ticket.close()
    }

    @Test fun cancelledQueuedRedrawCannotInvalidateItsSuccessor() = runTest {
        val f = Fixture()
        val old = f.barrier.begin(8)
        f.producers(8)
        val ticket = f.barrier.begin(9)
        f.run(0, evenCancelled = true)
        assertNull(old.awaitDraws())
        assertEquals(0, f.redraws)
        f.producers(9)
        ticket.close()
        f.run(0, evenCancelled = true)
        assertEquals(0, f.redraws)
        assertNull(ticket.awaitDraws())
    }

    @Test fun completionBeforeAwaitIsRetainedAndCancelsDeadline() = runTest {
        val f = Fixture()
        val ticket = f.barrier.begin(9)
        f.ready(9)
        f.run(0)
        f.run(500)
        assertNotNull(ticket.awaitDraws())
        assertTrue(ticket.isCurrent())
        ticket.close()
        assertFalse(ticket.isCurrent())
    }

    @Test fun removedOrHiddenProducerAbortsEvenQueuedSuccess() = runTest {
        val f = Fixture()
        val ticket = f.barrier.begin(9)
        f.ready(9)
        f.scene = f.scene.copy(producers = f.scene.producers - "lyrics")
        f.barrier.sceneChanged()
        f.run(0, evenCancelled = true)
        assertNull(ticket.awaitDraws())
        assertEquals("scene-changed", ticket.abortReason)
        assertFalse(ticket.isCurrent())
    }

    @Test fun changedGeometryOrInkIsRecheckedByTheQueuedCompletion() = runTest {
        val f = Fixture()
        val ticket = f.barrier.begin(9)
        f.ready(9)
        f.scene = f.scene.copy(geometry = "moved viewport")
        f.run(0)
        assertNull(ticket.awaitDraws())
        assertFalse(ticket.isCurrent())
    }

    @Test fun cancelledOldPostAndTimeoutCannotReleaseANewTicket() = runTest {
        val f = Fixture()
        val old = f.barrier.begin(8)
        f.ready(8)
        val ticket = f.barrier.begin(9)
        f.run(0, evenCancelled = true)
        assertNull(old.awaitDraws())
        val result = async(start = CoroutineStart.UNDISPATCHED) { ticket.awaitDraws() }
        assertFalse(result.isCompleted)
        f.ready(9)
        f.run(0)
        f.run(500, evenCancelled = true)
        assertNotNull(result.await())
        // A cancelled timeout cannot revoke a completed, still-current ticket.
        assertTrue(ticket.isCurrent())
        ticket.close()
    }

    @Test fun disposalAndCaptureCancellationRejectQueuedReadiness() = runTest {
        val f = Fixture()
        val disposed = f.barrier.begin(8)
        f.ready(8)
        f.barrier.dispose()
        f.run(0, evenCancelled = true)
        assertNull(disposed.awaitDraws())
        val ticket = f.barrier.begin(9)
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            try { ticket.awaitDraws() } finally { ticket.close() }
        }
        f.ready(9)
        result.cancel()
        result.join()
        f.run(0, evenCancelled = true)
        assertFalse(ticket.isCurrent())
        assertEquals("closed", ticket.abortReason)
    }

    @Test fun missingVisibleProducerTimesOutWithoutPublishingOrRetrying() = runTest {
        val f = Fixture()
        val ticket = f.barrier.begin(9)
        f.barrier.producerRecorded("marker", 9)
        f.barrier.compositeRecorded(9, f.draws(9))
        f.run(500)
        assertNull(ticket.awaitDraws())
        assertEquals("draw-timeout", ticket.abortReason)
        assertEquals(0, f.redraws)
        assertTrue(f.tasks.isEmpty())
    }

    @Test fun emptyVisibleProducerSetDoesNotWaitForHiddenDocuments() = runTest {
        val f = Fixture()
        f.scene = f.scene.copy(producers = emptyMap())
        val ticket = f.barrier.begin(9)
        f.barrier.compositeRecorded(9, emptyMap())
        f.run(0)
        assertEquals(emptyMap<Any, BackdropProducerDraws>(), ticket.awaitDraws()?.producers)
        assertEquals(0, f.redraws)
        ticket.close()
    }

    @Test fun changesAfterReadinessStillInvalidatePublication() = runTest {
        val f = Fixture()
        val ticket = f.barrier.begin(9)
        f.ready(9)
        f.run(0)
        val evidence = ticket.awaitDraws()
        f.scene = f.scene.copy(geometry = "different viewport")
        assertFalse(ticket.isCurrent())
        assertEquals(9, evidence?.composite)
        assertEquals(1, f.redraws)
        ticket.close()
    }
}
