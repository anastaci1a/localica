package io.github.anastaci1a.localica.queue

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class OrderedJourneyTest {
    private val ids = listOf("A", "B", "C", "D", "E", "F", "X")
    private fun shuffled(): QueueJourney {
        var path = QueueJourney.start(ids, "A", JourneyModes(shuffle = true, loop = true), Random(19))
        for (id in listOf("D", "B", "F")) path = path.select(id, ids, Random(23))
        return path.arrive(path.history[1].token, ids)
    }
    private fun off(path: QueueJourney, order: List<String> = ids) =
        path.reconcile(order, path.current?.entryId, path.modes.copy(shuffle = false), Random(29))

    @Test fun disablingAfterBackProjectsQueueNeighborsWithoutInventingHistory() {
        val before = shuffled()
        var path = off(before)
        assertEquals(before.current, path.current)
        assertEquals(before.history, path.history)
        assertEquals(before.cursor, path.cursor)
        assertEquals("C", path.previous?.entryId)
        assertEquals("E", path.next?.entryId)
        assertTrue(path.modes.loop)
        val window = path.window()
        val current = window.indexOf(path.current)
        assertEquals(path.previous, window[current - 1])
        assertEquals(path.next, window[current + 1])
        assertEquals(path, path.arrive(before.history[2].token, ids))
        val next = path.next!!
        path = path.arrive(next.token, ids)
        assertEquals(before.history + next, path.history)
        path = path.arrive(path.previous!!.token, ids)
        assertEquals(before.current, path.current)
        assertEquals(next, path.next)
        assertEquals(before.history + next, path.history)
    }

    @Test fun enablingAgainStartsFreshAtExactCurrentVisitAndDoesNotResurrectOldFuture() {
        var path = off(shuffled())
        repeat(4) {
            val anchor = path.current!!
            val namespace = path.orderedTraversalId
            val stale = path.next!!.token
            val on = path.reconcile(ids, anchor.entryId, path.modes.copy(shuffle = true), Random(it))
            assertEquals(listOf(anchor), on.history)
            assertEquals(setOf(anchor.entryId), on.visited)
            assertNull(on.orderedTraversalId)
            assertNull(on.orderedAnchorToken)
            assertEquals(ids.toSet() - anchor.entryId, on.future.filter { it.cycle == anchor.cycle }.map { it.entryId }.toSet())
            path = off(on)
            assertEquals(anchor, path.current)
            assertNotEquals(namespace, path.orderedTraversalId)
            assertNotEquals(stale, path.next!!.token)
            assertEquals(path, path.arrive(stale, ids))
        }
    }

    @Test fun removingFirstVisitOrAnchorDoesNotRetokenUnchangedNeighbors() {
        var path = off(shuffled())
        path = path.arrive(path.next!!.token, ids)
        val next = path.next
        val namespace = path.orderedTraversalId
        val withoutFirst = ids - "A"
        path = path.reconcile(withoutFirst, "E", path.modes)
        assertEquals(next, path.next)
        val withoutAnchor = withoutFirst - "D"
        path = path.reconcile(withoutAnchor, "E", path.modes)
        assertEquals(namespace, path.orderedTraversalId)
        assertEquals(next, path.next)
        assertEquals("C", path.previous?.entryId)
        assertTrue(path.history.none { it.entryId == "A" || it.entryId == "D" })
    }

    @Test fun missingCurrentStartsNewOrderedAnchorWithoutReusingTheRemovedVisit() {
        val original = QueueJourney.start(ids, "D", JourneyModes(loop = true))
        val kept = ids - "D"
        var path = original.reconcile(kept, "D", original.modes)
        val selected = path.current!!
        assertEquals("A", selected.entryId)
        assertNotEquals(original.orderedTraversalId, path.orderedTraversalId)
        path = path.arrive(path.next!!.token, kept)
        assertEquals(selected, path.previous)
        assertEquals(path, path.arrive(original.current!!.token, kept))
    }

    @Test fun repeatedManualVisitsUseTheirExplicitAnchorRatherThanAnArbitraryMatchingPair() {
        var path = QueueJourney.start(ids, "B", JourneyModes())
        val original = path.current!!
        path = path.select("D", ids).select("B", ids)
        val latest = path.current!!
        assertNotEquals(original.token, latest.token)
        path = path.arrive(path.previous!!.token, ids)
        assertEquals(latest, path.next)
        path = path.arrive(path.next!!.token, ids)
        assertEquals(latest, path.current)
        assertEquals(path.history.size, path.history.map { it.token }.toSet().size)
    }

    @Test fun repeatWrapsHaveDistinctSignedCyclesIncludingSingleOccurrence() {
        for (order in listOf(listOf("A"), listOf("A", "B", "C"))) {
            var path = QueueJourney.start(order, "A", JourneyModes(loop = true))
            val anchor = path.current!!
            val previous = path.previous!!
            assertEquals(order.last(), previous.entryId)
            assertEquals(-1L, previous.cycle)
            assertNotEquals(anchor.token, previous.token)
            assertEquals(129, path.window().size)
            assertEquals(129, path.window().map { it.token }.toSet().size)
            path = path.arrive(previous.token, order)
            assertEquals(anchor, path.next)
            path = path.arrive(path.next!!.token, order)
            assertEquals(anchor, path.current)
            assertEquals(anchor.cycle, path.cycle)
            assertEquals(path, path.reconcile(order, "A", path.modes))
            val restarted = path.restart(order, order.first())
            assertEquals(anchor.cycle + 1, restarted.current!!.cycle)
            assertEquals(restarted.current!!.cycle, restarted.cycle)
        }
        val stopped = QueueJourney.start(ids, ids.first(), JourneyModes())
        assertNull(stopped.previous)
        assertNull(QueueJourney.start(ids, ids.last(), JourneyModes()).next)
    }

    @Test fun recordedBackwardWrapRestoresActiveCycleBeforeResetOrModeChange() {
        val order = listOf("A", "B", "C")
        var path = QueueJourney.start(order, "C", JourneyModes(loop = true))
        val anchor = path.current!!
        repeat(4) { path = path.arrive(path.next!!.token, order) }
        assertEquals(2L, path.cycle)
        repeat(4) {
            path = path.arrive(path.previous!!.token, order)
            assertEquals(path.current!!.cycle, path.cycle)
            assertEquals(path, path.reconcile(order, path.current!!.entryId, path.modes))
        }
        assertEquals(anchor, path.current)
        assertEquals(0L, path.cycle)
        assertEquals(1L, path.restart(order, "A").cycle)
        val enabled = path.reconcile(order, "C", path.modes.copy(shuffle = true))
        assertEquals(anchor, enabled.current)
        assertEquals(0L, enabled.cycle)
    }

    @Test fun extremeRestoredCyclesKeepBoundedPlansWithoutArithmeticWraparound() {
        val order = listOf("A", "B")
        val initial = QueueJourney.start(order, "A", JourneyModes(loop = true))
        for (number in listOf(Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE - 1, Long.MAX_VALUE)) {
            val visit = initial.current!!.copy(cycle = number)
            val restored = initial.copy(history = listOf(visit), cycle = number, plans = emptyMap(), future = emptyList())
                .reconcile(order, "A", initial.modes)
            assertEquals(visit, restored.current)
            assertTrue(restored.plans.isNotEmpty())
            assertTrue(restored.plans.size <= 3)
            assertTrue(restored.window().size <= 129)
            assertEquals(number, restored.next!!.cycle)
            if (number == Long.MIN_VALUE) assertNull(restored.previous)
            if (number == Long.MAX_VALUE) {
                val last = restored.arrive(restored.next!!.token, order)
                assertNull(last.next)
                try { last.restart(order, "A"); fail("Cycle overflow must fail without mutating the saved journey") }
                catch (_: IllegalStateException) { }
                assertEquals(Long.MAX_VALUE, last.cycle)
            }
        }
    }

    @Test fun explicitOrderAndArbitraryOccurrenceIdsRemainDistinct() {
        val order = listOf("duplicate-track:1", "a\u0000b:1", "a:1\u0000b", "duplicate-track:2", "🎵")
        var path = QueueJourney.start(order, order[2], JourneyModes(loop = true))
        assertEquals(order[1], path.previous?.entryId)
        assertEquals(order[3], path.next?.entryId)
        assertEquals(path.window().size, path.window().map { it.token }.toSet().size)
        path = path.reconcile(order.reversed(), order[2], path.modes)
        assertEquals(order[3], path.previous?.entryId)
        assertEquals(order[1], path.next?.entryId)
    }

    @Test fun legacyOffHistoryNormalizesWithoutLosingItsRealCurrentVisit() {
        val legacy = shuffled().copy(modes = JourneyModes(loop = true), orderedTraversalId = null, orderedAnchorToken = null)
        val normalized = legacy.reconcile(ids, "D", legacy.modes)
        assertEquals(legacy.history, normalized.history)
        assertEquals(legacy.current, normalized.current)
        assertEquals("C", normalized.previous?.entryId)
        assertEquals("E", normalized.next?.entryId)
        assertEquals(normalized, normalized.reconcile(ids, "D", normalized.modes))
    }
}
