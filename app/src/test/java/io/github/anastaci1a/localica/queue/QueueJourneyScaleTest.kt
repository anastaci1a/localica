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
            assertEquals(if (loop && shuffle) 3 else 1, plans.size)
            plans.values.forEach { order ->
                assertEquals(ids.size, order.size)
                assertEquals(ids.toSet(), order.toSet())
                if (!shuffle) assertEquals(ids, order)
            }
            val tokens = planTokens(path, ids)
            path = path.reconcile(ids, path.current?.entryId, modes, Random(23))
            assertEquals(plans, path.plans)
            assertEquals(tokens, planTokens(path, ids))
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
    private fun planTokens(start: QueueJourney, ids: List<String>): Map<Pair<Long, String>, String> {
        val expected = start.plans.flatMap { (cycle, order) -> order.map { cycle to it } }.toSet()
        val tokens = mutableMapOf<Pair<Long, String>, String>()
        var path = start
        var steps = 0
        while (tokens.size < expected.size) {
            path.window().forEach { visit ->
                val key = visit.cycle to visit.entryId
                if (key in expected) tokens.put(key, visit.token)?.let { assertEquals("Stable token for $key", it, visit.token) }
            }
            if (tokens.size == expected.size) break
            val next = checkNotNull(path.next) { "Missing planned visits after $steps transitions" }
            path = path.arrive(next.token, ids, Random(29))
            assertEquals(next, path.current)
            assertTrue("Traversal must cover its finite plans", ++steps <= expected.size)
        }
        assertEquals(expected, tokens.keys)
        assertEquals("Tokens are unique across occurrences and cycles", tokens.size, tokens.values.toSet().size)
        println("RELEASE_REVIEW_TOKENS shuffle=${start.modes.shuffle} loop=${start.modes.loop} entries=${ids.size} cycles=${start.plans.size} tokens=${tokens.size} transitions=$steps")
        return tokens
    }
}
