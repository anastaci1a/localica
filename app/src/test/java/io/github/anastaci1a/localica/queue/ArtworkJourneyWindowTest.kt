package io.github.anastaci1a.localica.queue

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class ArtworkJourneyWindowTest {
    private val ids = (0..11).map { "occurrence-$it" }

    @Test fun orderedWindowIsNearestFirstFourEachWayWithoutChangingHistory() {
        val path = QueueJourney.start(ids, ids[5], JourneyModes(), Random(7))
        assertEquals(listOf(6, 4, 7, 3, 8, 2, 9, 1).map(ids::get),
            path.artworkWindow().map { it.entryId })
        assertEquals(listOf(ids[5]), path.history.map { it.entryId })
    }

    @Test fun shuffledWindowUsesActualVisitsAndFutureAfterBackwardTravel() {
        var path = QueueJourney.start(ids, ids[0], JourneyModes(shuffle = true), Random(8))
        repeat(5) { path = path.arrive(path.next!!.token, ids, Random(it)) }
        path = path.arrive(path.previous!!.token, ids, Random(9))
        val window = path.window()
        val center = window.indexOfFirst { it.token == path.current!!.token }
        assertEquals((1..4).flatMap { listOfNotNull(window.getOrNull(center + it), window.getOrNull(center - it)) },
            path.artworkWindow())
        assertEquals(path.next, path.artworkWindow()[0])
        assertEquals(path.previous, path.artworkWindow()[1])
    }

    @Test fun edgesRepeatAndReorderRetainOccurrenceIdentity() {
        var path = QueueJourney.start(ids, ids[0], JourneyModes(), Random(2))
        assertEquals(ids.drop(1).take(4), path.artworkWindow().map { it.entryId })
        val reordered = ids.reversed().filterNot { it == ids[1] }
        path = path.reconcile(reordered, ids[0], path.modes, Random(3))
        assertTrue(path.artworkWindow().all { it.entryId in reordered })
        assertEquals(listOf(ids[2], ids[3], ids[4], ids[5]), path.artworkWindow().map { it.entryId })
        val repeat = QueueJourney.start(listOf("same-file-1", "same-file-2"), "same-file-1",
            JourneyModes(loop = true), Random(4))
        assertEquals(8, repeat.artworkWindow().size)
        assertEquals(8, repeat.artworkWindow().map { it.token }.distinct().size)
    }
}
