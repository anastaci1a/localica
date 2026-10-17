package io.github.anastaci1a.localica

import io.github.anastaci1a.localica.queue.*
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class QueueJourneyTest {
    private val ids = listOf("A", "B", "C", "D", "E", "F", "X")
    private val random = Random(19)

    @Test fun backwardAndForwardRetraceWhileManualSelectionBranchesWithoutForgettingVisitedSongs() {
        var path = QueueJourney.start(ids, "A", JourneyModes(shuffle = true), random)
        for (id in listOf("D", "B", "F")) path = path.select(id, ids, random)
        path = path.arrive(path.history[1].token, ids, random)
        assertEquals("D", path.current?.entryId)
        assertEquals("B", path.next?.entryId)
        path = path.arrive(path.next!!.token, ids, random)
        assertEquals("F", path.next?.entryId)
        path = path.arrive(path.history[1].token, ids, random).select("X", ids, random)
        assertEquals(listOf("A", "D", "X"), path.history.map { it.entryId })
        assertEquals(setOf("A", "D", "B", "F", "X"), path.visited)
        assertEquals(setOf("C", "E"), path.future.map { it.entryId }.toSet())
    }

    @Test fun restartBeginsAtOnlyTheKnownCursorAndForwardVisitsAreDistinctAcrossCycles() {
        val order = listOf("A", "B", "C")
        var path = QueueJourney.start(order, "B", JourneyModes(loop = true), random)
        assertEquals("A", path.previous?.entryId)
        assertEquals(listOf("B"), path.history.map { it.entryId })
        assertEquals("C", path.next?.entryId)
        path = path.arrive(path.next!!.token, order, random)
        assertEquals("A", path.next?.entryId)
        path = path.arrive(path.next!!.token, order, random)
        assertEquals(1L, path.cycle)
        assertEquals(setOf("A"), path.visited)
        assertEquals("B", path.next?.entryId)
        assertNotEquals(path.history.first().token, path.next!!.token)
        path = path.arrive(path.history.first().token, order, random)
        assertEquals("C", path.next?.entryId)
        assertEquals(0L, path.cycle)
    }

    @Test fun editsKeepValidForwardHistoryAndStableFutureTokens() {
        var path = QueueJourney.start(ids, "A", JourneyModes(shuffle = true), random)
        path = path.select("D", ids, random).select("B", ids, random).select("F", ids, random)
        path = path.arrive(path.history[1].token, ids, random)
        val pending = path.future.associate { it.entryId to it.token }
        val changed = ids.reversed().filterNot { it == "B" } + "new"
        path = path.reconcile(changed, "D", path.modes, random)
        assertEquals(listOf("A", "D", "F"), path.history.map { it.entryId })
        assertEquals("F", path.next?.entryId)
        assertEquals("D", path.current?.entryId)
        assertFalse("B" in path.visited)
        assertTrue(path.future.any { it.entryId == "new" })
        path.future.filter { it.entryId in pending }.forEach { assertEquals(pending[it.entryId], it.token) }
    }

    @Test fun playerWindowIsBoundedButOldHistoryRemainsReachable() {
        val order = (0..300).map { it.toString() }
        var path = QueueJourney.start(order, order.first(), JourneyModes(), random)
        repeat(200) { path = path.arrive(path.next!!.token, order, random) }
        assertEquals(201, path.history.size)
        assertTrue(path.window().size <= 129)
        repeat(200) { path = path.arrive(path.previous!!.token, order, random) }
        assertEquals("0", path.current?.entryId)
        assertEquals("1", path.next?.entryId)
        assertEquals(201, path.history.size)
    }

    @Test fun explicitSelectionAfterExhaustionStartsAnotherShuffleCycle() {
        val order = listOf("A", "B", "C")
        var path = QueueJourney.start(order, "A", JourneyModes(shuffle = true), random)
        repeat(2) { path = path.arrive(path.next!!.token, order, random) }
        assertNull(path.next)
        path = path.select("B", order, random)
        assertEquals(1L, path.cycle)
        assertEquals(setOf("B"), path.visited)
        assertEquals(setOf("A", "C"), path.future.map { it.entryId }.toSet())
    }
}
