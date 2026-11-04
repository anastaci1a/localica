package io.github.anastaci1a.localica.queue

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class PlayNextJourneyModelTest {
    @Test fun nextBatchPrecedesForwardHistoryWithoutLosingVisitsOrPendingTokens() {
        val original = listOf("A", "B", "C", "D")
        var path = QueueJourney.start(original, "A", JourneyModes(shuffle = true), Random(3))
            .select("B", original).select("C", original)
        path = path.arrive(path.history.first().token, original)
        val history = path.history
        val pending = path.future
        val visited = path.visited
        val ids = listOf("A", "X", "Y", "B", "C", "D")
        path = path.playNext(listOf("X", "Y"), ids, Random(4))
        assertEquals(history.first(), path.current)
        assertEquals(visited, path.visited)
        assertEquals(listOf("A", "X", "Y", "B", "C"), path.history.map { it.entryId })
        assertEquals(history.drop(1), path.history.drop(3))
        assertEquals(pending, path.future)
        assertEquals(path, path.reconcile(ids, "A", path.modes))
        val secondIds = listOf("A", "Z", "W", "X", "Y", "B", "C", "D")
        path = path.playNext(listOf("Z", "W"), secondIds)
        for (id in listOf("Z", "W", "X", "Y", "B", "C", "D")) {
            assertEquals(id, path.next!!.entryId)
            path = path.arrive(path.next!!.token, secondIds).reconcile(secondIds, id, path.modes)
            assertTrue(id in path.visited)
            assertEquals(path.window().size, path.window().map { it.token }.toSet().size)
        }
        assertNull(path.next)
    }

    @Test fun pendingPlanAndRepeatCyclesKeepDistinctOccurrenceVisits() {
        for (reshuffle in listOf(false, true)) {
            val ids = listOf("A", "B", "C")
            var path = QueueJourney.start(ids, "A", JourneyModes(true, true, reshuffle), Random(7))
            val next = path.next
            val updated = listOf("A", "X", "Y", "B", "C")
            path = path.playNext(listOf("X", "Y"), updated)
            val first = path.next!!
            assertEquals("X", first.entryId)
            path = path.arrive(first.token, updated)
            assertEquals("Y", path.next!!.entryId)
            path = path.arrive(path.next!!.token, updated)
            assertEquals(next, path.next)
            val visits = mutableListOf(path.current!!)
            repeat(12) {
                path = path.arrive(path.next!!.token, updated).reconcile(updated, path.next!!.entryId, path.modes)
                visits += path.current!!
            }
            assertEquals(visits.size, visits.map { it.token }.toSet().size)
            visits.groupBy { it.cycle }.values.forEach { cycle ->
                assertEquals(cycle.size, cycle.map { it.entryId }.toSet().size)
            }
            assertTrue(visits.any { it.entryId == "X" && it.token != first.token })
        }
    }

    @Test fun removingOrSelectingPastPendingNextVisitsDoesNotMarkThemPlayed() {
        val original = listOf("A", "B", "C")
        val ids = listOf("A", "X", "Y", "B", "C")
        val path = QueueJourney.start(original, "A", JourneyModes(shuffle = true), Random(1))
            .playNext(listOf("X", "Y"), ids)
        val removed = path.reconcile(ids - "X", "A", path.modes)
        assertEquals("Y", removed.next!!.entryId)
        val selected = path.select("B", ids)
        assertEquals(setOf("A", "B"), selected.visited)
        // Selecting elsewhere keeps unplayed explicit requests ahead of the random plan.
        assertEquals(listOf("X", "Y"), selected.history.drop(selected.cursor + 1).map { it.entryId })
        assertEquals(listOf("C"), selected.future.map { it.entryId })
        val ordered = path.reconcile(ids, "A", JourneyModes())
        assertEquals("X", ordered.next!!.entryId)
        val reset = ordered.reconcile(ids, "A", path.modes)
        assertEquals(listOf(path.current), reset.history)
        assertEquals(setOf("A"), reset.visited)
        assertEquals(setOf("X", "Y", "B", "C"), reset.future.map { it.entryId }.toSet())
    }

    @Test fun insertionAfterPreviousAcrossRepeatBoundaryKeepsLaterCycleVisits() {
        val ids = listOf("A", "B", "C")
        var path = QueueJourney.start(ids, "A", JourneyModes(shuffle = true, loop = true), Random(8))
        repeat(4) { path = path.arrive(path.next!!.token, ids) }
        val original = path.history
        path = path.arrive(original.first().token, ids)
        val membership = path.visited
        val updated = listOf("A", "X", "Y", "B", "C")
        path = path.playNext(listOf("X", "Y"), updated)
        for (id in listOf("X", "Y")) {
            assertEquals(id, path.next!!.entryId)
            assertEquals(1L, path.next!!.cycle)
            path = path.arrive(path.next!!.token, updated).reconcile(updated, id, path.modes)
            assertEquals(membership + listOf("X", "Y").take(if (id == "X") 1 else 2), path.visited)
        }
        for (visit in original.drop(1)) {
            assertEquals(visit, path.next)
            path = path.arrive(visit.token, updated)
        }
        // Requests made from older history belong to the active cycle and must not repeat there.
        assertFalse(path.future.any { it.entryId in listOf("X", "Y") && it.cycle == 1L })
    }
    @Test fun skipsIntoForwardHistoryKeepUnplayedRequestsButDoNotReservePlayedRetrace() {
        val original = listOf("A", "B", "C", "D")
        val ids = original + listOf("X", "Y")
        var path = QueueJourney.start(original, "A", JourneyModes(shuffle = true), Random(9))
            .select("B", original).select("C", original)
        val destination = path.current!!
        path = path.arrive(path.history.first().token, original).playNext(listOf("X", "Y"), ids)
        val reserved = path.history.drop(1).take(2)
        path = path.arrive(destination.token, ids)
        assertEquals(destination, path.current)
        assertEquals(reserved, path.history.drop(path.cursor + 1))
        path = path.arrive(path.next!!.token, ids)
        val selected = path.select("D", ids)
        assertEquals(listOf(reserved.last()), selected.history.drop(selected.cursor + 1))
        assertEquals(setOf("A", "B", "C", "D", "X"), selected.visited)
        val consumed = selected.select("Y", ids)
        assertNull(consumed.next)
    }

    @Test fun staleArrivalIntoNextCycleKeepsPendingRequestsOnceInDestinationCycle() {
        val original = listOf("A", "B")
        val ids = original + listOf("X", "Y")
        var path = QueueJourney.start(original, "A", JourneyModes(true, true), Random(2))
        path = path.arrive(path.next!!.token, original)
        val stale = path.next!!
        path = path.playNext(listOf("X", "Y"), ids)
        val tokens = path.history.drop(path.cursor + 1).map { it.token }
        path = path.arrive(stale.token, ids)
        assertEquals(tokens, path.history.drop(path.cursor + 1).map { it.token })
        assertEquals(listOf(1L, 1L), path.history.drop(path.cursor + 1).map { it.cycle })
        repeat(2) { path = path.arrive(path.next!!.token, ids) }
        assertTrue(path.future.none { it.cycle == 1L && it.entryId in listOf("X", "Y") })
    }

}
