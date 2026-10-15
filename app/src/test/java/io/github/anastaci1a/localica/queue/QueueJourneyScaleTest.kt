package io.github.anastaci1a.localica.queue

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class QueueJourneyScaleTest {
    /** Counts comparison work rather than asserting a device-dependent time budget. */
    private class CountedIds(private val values: List<String>) : AbstractList<String>() {
        var membershipComparisons = 0L
        override val size get() = values.size
        override fun get(index: Int) = values[index]
        override fun contains(element: String): Boolean {
            for (value in values) {
                membershipComparisons++
                if (value == element) return true
            }
            return false
        }
    }

    @Test fun sequentialPlanningDoesNotScanTheWholeOrderForEveryOccurrence() {
        for (loop in listOf(false, true)) {
            val ids = CountedIds(List(13_000) { "occurrence-$it" })
            val modes = JourneyModes(loop = loop)
            val started = QueueJourney.start(ids, ids.first(), modes, Random(19))
            val reconciled = started.reconcile(ids, started.current?.entryId, modes, Random(19))
            val arrived = reconciled.arrive(reconciled.next!!.token, ids, Random(19))
            assertEquals(ids[1], arrived.current?.entryId)
            assertTrue("Membership work must be linear, was ${ids.membershipComparisons}",
                ids.membershipComparisons <= ids.size * 12L)
        }
    }

    @Test fun largePlansRetainOrderMembershipTokensAndHistoryAcrossModes() {
        val ids = List(13_000) { "occurrence-$it" }
        for (shuffle in listOf(false, true)) for (loop in listOf(false, true)) {
            val modes = JourneyModes(shuffle = shuffle, loop = loop, reshuffle = true)
            var path = QueueJourney.start(ids, ids.first(), modes, Random(19))
            val plans = path.plans
            assertEquals(if (loop) 3 else 1, plans.size)
            plans.values.forEach { order ->
                assertEquals(ids.size, order.size)
                assertEquals(ids.toSet(), order.toSet())
                if (!shuffle) assertEquals(ids, order)
            }
            val tokens = path.future.associate { (it.cycle to it.entryId) to it.token }
            path = path.reconcile(ids, path.current?.entryId, modes, Random(23))
            assertEquals(plans, path.plans)
            path.future.forEach { assertEquals(tokens[it.cycle to it.entryId], it.token) }
            repeat(8) {
                val next = path.next!!
                path = path.arrive(next.token, ids, Random(29))
                assertEquals(next, path.current)
                assertTrue(path.window().size <= 129)
            }
            val forward = path.current
            val previous = path.previous!!
            path = path.arrive(previous.token, ids, Random(31))
            assertEquals(previous, path.current)
            assertEquals(forward, path.next)
            path = path.arrive(path.next!!.token, ids, Random(37))
            assertEquals(forward, path.current)
            assertEquals(9, path.visited.size)
        }
    }
}
