package io.github.anastaci1a.localica.queue

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

/**
 * Standalone JVM contract: compile with .github/compatibility/QueueJourney.kt, not the modern
 * production journey. QueueSettings and SongListView may be extracted unchanged from their
 * production declarations; no Android, controller, persistence or APK behavior is simulated here.
 */
class CompatibilityArtworkWindowContractTest {
    private fun visit(id: String, token: String, cycle: Long = 0) = QueueVisit(id, cycle, token)

    private fun assertVisits(expected: List<QueueVisit>, actual: List<QueueVisit>) {
        assertEquals(expected, actual)
        expected.zip(actual).forEach { (original, returned) -> assertSame(original, returned) }
    }

    @Test fun everyRadiusInterleavesActualForwardHistoryBeforeConcreteFuture() {
        val history = (0..5).map { visit("occurrence-$it", "history-$it") }
        val future = (6..10).map { visit("occurrence-$it", "future-$it", 1) }
        val path = QueueJourney(history, 3, future)
        val expected = listOf(history[4], history[2], history[5], history[1], future[0], history[0], future[1])
        val sizes = listOf(0, 2, 4, 6, 7)
        for (radius in 0..4) assertVisits(expected.take(sizes[radius]), path.artworkWindow(radius))
        assertVisits(expected, path.artworkWindow())
        assertSame(path.next, expected[0])
        assertSame(path.previous, expected[1])
    }

    @Test fun duplicateOccurrencesAndRepeatVisitsRetainTheirOwnTokensAndCycles() {
        val history = listOf(
            visit("same-file-occurrence-a", "a-first", -1),
            visit("same-file-occurrence-b", "b-first", -1),
            visit("same-file-occurrence-a", "a-current"),
            visit("same-file-occurrence-b", "b-again"),
        )
        val future = listOf(visit("same-file-occurrence-a", "a-next-cycle", 1),
            visit("same-file-occurrence-b", "b-next-cycle", 1))
        val path = QueueJourney(history, 2, future)
        val expected = listOf(history[3], history[1], future[0], history[0], future[1])
        assertVisits(expected, path.artworkWindow())
        assertEquals(listOf("b-again", "b-first", "a-next-cycle", "a-first", "b-next-cycle"),
            path.artworkWindow().map { it.token })
        assertFalse(path.current in path.artworkWindow())
    }

    @Test fun orderedPlansAndSchemaSevenIdentitiesNeverReplaceLegacyNeighbors() {
        val history = listOf(visit("A", "actual-a"), visit("D", "actual-d"),
            visit("B", "actual-b"), visit("F", "actual-f"))
        val future = listOf(visit("C", "pending-c"), visit("E", "pending-e"))
        val plans = mapOf(0L to listOf("A", "B", "C", "D", "E", "F"))
        val expected = listOf(history[2], history[0], history[3], future[0], future[1])
        for (shuffle in listOf(false, true)) {
            for (identities in listOf(null to null, "inert-generation" to "missing-anchor")) {
                val path = QueueJourney(history, 1, future, plans = plans,
                    modes = JourneyModes(shuffle = shuffle, loop = true),
                    orderedTraversalId = identities.first, orderedAnchorToken = identities.second)
                assertVisits(expected, path.artworkWindow())
                assertEquals("B", path.next!!.entryId)
                assertEquals("A", path.previous!!.entryId)
            }
        }
    }

    @Test fun boundedWindowFindsTheCurrentTokenBeyondSixtyFourHistoryVisits() {
        val history = (0..199).map { visit("occurrence-${it % 3}", "history-$it", it / 3L) }
        val future = listOf(visit("later", "future", 100))
        val path = QueueJourney(history, 100, future)
        assertEquals(129, path.window().size)
        assertVisits(listOf(history[101], history[99], history[102], history[98],
            history[103], history[97], history[104], history[96]), path.artworkWindow())
    }

    @Test fun edgesDoNotWrapInventNeighborsOrFillFromBeyondTheRadius() {
        val visits = (0..6).map { visit("occurrence-$it", "visit-$it") }
        for (radius in 0..4) {
            assertVisits(visits.drop(1).take(radius), QueueJourney(visits, 0).artworkWindow(radius))
            assertVisits(visits.dropLast(1).reversed().take(radius),
                QueueJourney(visits, visits.lastIndex).artworkWindow(radius))
            assertVisits(visits.drop(1).take(radius),
                QueueJourney(listOf(visits[0]), 0, visits.drop(1)).artworkWindow(radius))
            assertTrue(QueueJourney().artworkWindow(radius).isEmpty())
            assertTrue(QueueJourney(future = visits).artworkWindow(radius).isEmpty())
            assertTrue(QueueJourney(visits, -1).artworkWindow(radius).isEmpty())
            assertTrue(QueueJourney(listOf(visits[0]), 0).artworkWindow(radius).isEmpty())
        }
        val onePast = QueueJourney(visits.take(2), 1, visits.drop(2), modes = JourneyModes(loop = true))
        assertVisits(listOf(visits[2], visits[0], visits[3], visits[4], visits[5]), onePast.artworkWindow())
    }

    @Test fun invalidRadiiAreRejectedEvenWithoutACurrentVisit() {
        val populated = QueueJourney(listOf(visit("A", "current")), 0,
            listOf(visit("B", "future")))
        for (path in listOf(QueueJourney(), populated)) {
            val before = path.copy()
            for (radius in listOf(Int.MIN_VALUE, -1, 5, Int.MAX_VALUE)) {
                assertThrows(IllegalArgumentException::class.java) { path.artworkWindow(radius) }
                assertEquals(before, path)
            }
        }
    }

    @Test fun queriesLeaveAllDurableStateAndLegacyTransportUnchanged() {
        val history = (0..8).map { visit("occurrence-${it % 3}", "history-$it", it / 3L) }.toMutableList()
        val future = (9..14).map { visit("occurrence-${it % 3}", "future-$it", it / 3L) }.toMutableList()
        val visited = mutableSetOf("occurrence-0", "occurrence-1")
        val plans = mutableMapOf(1L to mutableListOf("occurrence-2", "occurrence-0", "occurrence-1"))
        val path = QueueJourney(history, 4, future, cycle = 1, visited = visited, plans = plans,
            modes = JourneyModes(shuffle = true, loop = true, reshuffle = true),
            orderedTraversalId = "untouched-generation", orderedAnchorToken = "untouched-anchor")
        val before = path.copy(history = history.map { it.copy() }, future = future.map { it.copy() },
            visited = visited.toSet(), plans = plans.mapValues { (_, ids) -> ids.toList() })
        val window = path.window().toList()
        val current = path.current
        val previous = path.previous
        val next = path.next
        repeat(3) { for (radius in 0..4) path.artworkWindow(radius) }
        for (radius in listOf(-1, 5)) assertThrows(IllegalArgumentException::class.java) { path.artworkWindow(radius) }
        assertEquals(before, path)
        assertVisits(window, path.window())
        assertSame(history, path.history)
        assertSame(future, path.future)
        assertSame(visited, path.visited)
        assertSame(plans, path.plans)
        assertSame(current, path.current)
        assertSame(previous, path.previous)
        assertSame(next, path.next)
    }

    @Test fun legacySelectionAndBackwardArrivalKeepActualNotModernOrderedTraversal() {
        val ids = listOf("A", "B", "C", "D", "E", "F")
        var path = QueueJourney.start(ids, "A", JourneyModes(loop = true), Random(7))
        assertNull(path.previous)
        for (id in listOf("D", "B", "F")) path = path.select(id, ids, Random(8))
        path = path.arrive(path.history[1].token, ids, Random(9))
        val before = path.copy()
        assertVisits(listOf(path.history[2], path.history[0], path.history[3], path.future[0], path.future[1]),
            path.artworkWindow())
        assertEquals(listOf("B", "A", "F", "A", "B"), path.artworkWindow().map { it.entryId })
        assertEquals(before, path)
    }

    @Test fun nullableControllerCallShapeResolvesVisitEntryIds() {
        val path: QueueJourney? = QueueJourney(listOf(visit("A", "current")), 0,
            listOf(visit("B", "first"), visit("B", "repeat", 1), visit("C", "second")))
        val uris = mapOf("A" to "current-uri", "B" to "shared-uri", "C" to "other-uri")
        val visits: List<QueueVisit> = path?.artworkWindow().orEmpty()
        assertEquals(3, visits.size)
        assertEquals(listOf("shared-uri", "other-uri"), visits.mapNotNull { uris[it.entryId] }.distinct())
        val absent: QueueJourney? = null
        assertTrue(absent?.artworkWindow().orEmpty().mapNotNull { uris[it.entryId] }.distinct().isEmpty())
    }
}
