package io.github.anastaci1a.localica.queue

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

/** The plan key that carries the entry behind the shuffle start; the tests pin its persisted shape. */
private const val BEHIND = Long.MIN_VALUE

/** #826: Previous past the song playing when shuffle was enabled follows the queue's order behind it. */
class QueueJourneyShufflePreviousTest {
    private val ids = ('A'..'J').map { it.toString() }

    /** Ordered playback at [current], then shuffle turned on mid-queue, exactly as the player's Shuffle button does. */
    private fun shuffled(current: String = "E", loop: Boolean = false, queue: List<String> = ids): QueueJourney =
        QueueJourney.start(queue, current, JourneyModes(loop = loop), Random(7))
            .reconcile(queue, current, JourneyModes(shuffle = true, loop = loop), Random(11))

    private fun QueueJourney.back(queue: List<String> = ids) = arrive(previous!!.token, queue, Random(13))
    private fun QueueJourney.forward(queue: List<String> = ids) = arrive(next!!.token, queue, Random(17))
    /** Entries visited by pressing Next until the shuffle has nothing left. */
    private fun QueueJourney.drain(queue: List<String> = ids): Pair<QueueJourney, List<String>> {
        var path = this
        val entries = mutableListOf<String>()
        while (path.next != null && entries.size <= queue.size * 2) { path = path.forward(queue); entries += path.current!!.entryId }
        return path to entries
    }

    @Test fun previousFromTheShuffleStartWalksTheQueueOrderBehindItUntilTheQueueStart() {
        var path = shuffled("E")
        assertEquals(listOf("E"), path.history.map { it.entryId })
        for (expected in listOf("D", "C", "B", "A")) {
            assertEquals(expected, path.previous?.entryId)
            path = path.back()
            assertEquals(expected, path.current?.entryId)
        }
        assertNull("Nothing precedes the first song with repeat off", path.previous)
        assertEquals(listOf("A", "B", "C", "D", "E"), path.history.map { it.entryId })
        assertEquals(0, path.cursor)
    }

    @Test fun theUnplayedPreviousVisitIsStableAndIsPartOfTheNativeWindow() {
        val path = shuffled("E")
        val previous = path.previous!!
        assertEquals(previous, path.previous)
        assertEquals(previous, path.copy().previous)
        assertEquals(listOf("D", "E"), path.window().take(2).map { it.entryId })
        assertEquals(previous.token, path.window().first().token)
        assertTrue(previous.token in path.artworkWindow().map { it.token })
        assertNotEquals(previous.token, shuffled("E").previous!!.token)
        assertEquals("The planned shuffle never lists the visit twice", 1, path.window().count { it.token == previous.token })
        assertEquals(setOf("A", "B", "C", "D", "F", "G", "H", "I", "J"), path.future.map { it.entryId }.toSet())
    }

    @Test fun previousFromPlayedShuffleHistoryRetracesFirstAndOnlyThenFollowsTheQueue() {
        var path = shuffled("E")
        path = path.forward().forward()
        val played = path.history.map { it.entryId }
        assertEquals(3, played.size)
        path = path.back().back()
        assertEquals("E", path.current?.entryId)
        assertEquals("Retracing never invents or drops played songs", played, path.history.map { it.entryId })
        path = path.back()
        assertEquals("D", path.current?.entryId)
        assertEquals(played, path.history.drop(1).map { it.entryId })
        path = path.back()
        assertEquals("C", path.current?.entryId)
        assertEquals(played, path.history.drop(2).map { it.entryId })
    }

    @Test fun nextAfterGoingBackRetracesThenResumesTheShuffleWithoutRepeatingAnySong() {
        var path = shuffled("E").forward().forward()
        val shufflePart = path.history.drop(1).map { it.entryId }
        path = path.back().back().back().back()
        assertEquals("C", path.current?.entryId)
        val retraced = mutableListOf<String>()
        repeat(2 + shufflePart.size) { path = path.forward(); retraced += path.current!!.entryId }
        assertEquals(listOf("D", "E") + shufflePart, retraced)
        // A shuffled song may also be the queue neighbor reached by going back; Next still never replays any visited song.
        val unplayed = ids.toSet() - path.visited
        val (end, rest) = path.drain()
        assertEquals("Next plays every song still unvisited, once each", unplayed.size, rest.size)
        assertEquals(unplayed, rest.toSet())
        assertNull(end.next)
    }

    @Test fun songsReachedByGoingBackCountAsVisitedSoNextNeverReplaysThemInTheSameCycle() {
        var path = shuffled("E").back().back().back()
        assertEquals(setOf("E", "D", "C", "B"), path.visited)
        assertTrue(path.future.none { it.entryId in path.visited })
        repeat(3) { path = path.forward() }
        assertEquals("E", path.current?.entryId)
        val (end, rest) = path.drain()
        assertEquals(setOf("A", "F", "G", "H", "I", "J"), rest.toSet())
        assertEquals(6, rest.size)
        assertNull(end.next)
    }

    @Test fun repeatOffStopsWhenTheShuffledOrderIsExhausted() {
        val (end, _) = shuffled("E").back().back().drain()
        assertEquals(0L, end.cycle)
        assertNull(end.next)
        assertEquals(1, end.plans.keys.count { it != BEHIND })
        assertEquals(ids.toSet(), end.visited)
    }

    @Test fun repeatAllContinuesIntoAnotherCycleAfterExhaustion() {
        var end = shuffled("E", loop = true).back().back()
        repeat(ids.size * 2) { if (end.next!!.cycle == end.cycle) end = end.forward() }
        assertEquals(0L, end.cycle)
        assertEquals("The whole cycle was visited before a new one begins", ids.toSet(), end.visited)
        val next = end.next!!
        assertEquals(1L, next.cycle)
        val after = end.forward()
        assertEquals(1L, after.cycle)
        assertEquals(setOf(next.entryId), after.visited)
        assertEquals(ids.size - 1, after.future.count { it.cycle == 1L })
    }

    @Test fun repeatOneAndSongRepeatDoNotExtendTheCycle() {
        val oneSong = QueueSettings(shuffle = true, repeat = QueueRepeat.One)
        val songRepeat = QueueSettings(shuffle = true, advancedRepeat = AdvancedRepeat(songEnd = SongEnd.Repeat))
        for (settings in listOf(oneSong, songRepeat)) {
            val modes = JourneyModes.from(settings)
            assertFalse(modes.loop)
            val started = QueueJourney.start(ids, "E", modes.copy(shuffle = false), Random(7))
                .reconcile(ids, "E", modes, Random(11))
            val (end, _) = started.back().drain()
            assertNull(end.next)
            assertEquals(0L, end.cycle)
        }
        val all = JourneyModes.from(QueueSettings(shuffle = true, repeat = QueueRepeat.All))
        assertTrue(all.loop)
    }

    @Test fun repeatAllWrapsPreviousBehindTheFirstSongJustLikeOrderedTransport() {
        var path = shuffled("B", loop = true)
        path = path.back()
        assertEquals("A", path.current?.entryId)
        assertEquals("J", path.previous?.entryId)
        assertNull("A single song has nowhere to go", shuffled("A", loop = true, queue = listOf("A")).previous)
        assertNull("Repeat off stops at the first song", shuffled("A").previous)
    }

    @Test fun disablingShuffleKeepsTheCanonicalNeighborsOfNumberTwoNinetyFive() {
        val path = shuffled("E").back().back()
        assertEquals("C", path.current?.entryId)
        val ordered = path.reconcile(ids, "C", JourneyModes(), Random(23))
        assertEquals("B", ordered.previous?.entryId)
        assertEquals("D", ordered.next?.entryId)
        assertFalse(BEHIND in ordered.plans)
        assertEquals(setOf(0L), ordered.plans.keys)
        val again = ordered.reconcile(ids, "C", JourneyModes(shuffle = true), Random(29))
        assertEquals(listOf("C"), again.history.map { it.entryId })
        assertEquals("B", again.previous?.entryId)
    }

    @Test fun reshuffleAndCycleRestartsKeepTheWayBack() {
        val modes = JourneyModes(shuffle = true, reshuffle = true)
        var path = QueueJourney.start(ids, "E", JourneyModes(), Random(7)).reconcile(ids, "E", modes, Random(11))
        val changed = path.reconcile(ids, "E", modes.copy(reshuffle = false), Random(12))
        assertEquals("D", changed.previous?.entryId)
        val (end, _) = path.drain()
        path = end.select("B", ids, Random(31))
        assertEquals(1L, path.cycle)
        path = generateSequence(path) { if (it.cursor > 0) it.back() else null }.last()
        assertEquals("E", path.current?.entryId)
        assertEquals("Going back still reaches the song before the shuffle start", "D", path.previous?.entryId)
    }

    @Test fun queueEditsMoveTheNeighborAsTheyDoForOrderedTransport() {
        val path = shuffled("E")
        val without = ids - "D"
        assertEquals("C", path.reconcile(without, "E", path.modes, Random(3)).previous?.entryId)
        val inserted = ids.take(4) + "NEW" + ids.drop(4)
        assertEquals("NEW", path.reconcile(inserted, "E", path.modes, Random(3)).previous?.entryId)
        val atStart = path.reconcile(listOf("E") + (ids - "E"), "E", path.modes, Random(3))
        assertNull(atStart.previous)
        val restored = atStart.reconcile(ids, "E", path.modes, Random(3))
        assertEquals("Edits that restore the neighbor restore Previous", "D", restored.previous?.entryId)
    }

    @Test fun savedJourneysKeepGoingBackAfterRestore() {
        var path = shuffled("E").forward().back()
        repeat(4) {
            val restored = path.record("q").restore(path.visitRecords("q"), path.cycleRecords("q"))
            assertEquals(path, restored)
            assertEquals(path.previous, restored.previous)
            assertEquals(path.window(), restored.window())
            path = restored.back()
        }
        assertEquals("A", path.current?.entryId)
        val first = path.record("q").restore(path.visitRecords("q"), path.cycleRecords("q"))
        assertNull("Nothing precedes the first song", first.previous)
        assertEquals(path.history, first.history)
    }

    @Test fun shufflingFromTheStartKeepsTheExistingBehaviorBecauseThereIsNoModeChangeAnchor() {
        val path = QueueJourney.start(ids, "E", JourneyModes(shuffle = true), Random(7))
        assertNull(path.previous)
        assertFalse(BEHIND in path.plans)
    }

    @Test fun theNativeWindowKeepsItsBoundAroundTheRetainedBehindVisit() {
        val long = (0..300).map { "s$it" }
        var path = QueueJourney.start(long, "s150", JourneyModes(), Random(7)).reconcile(long, "s150", JourneyModes(shuffle = true), Random(11))
        repeat(140) { path = path.arrive(path.next!!.token, long, Random(13)) }
        for (cursor in listOf(0, 1, 63, 64, 65, 66, 100)) {
            val at = path.arrive(path.history[cursor].token, long, Random(17))
            assertEquals(cursor, at.cursor)
            val window = at.window()
            assertTrue("cursor $cursor window ${window.size}", window.size <= 129)
            assertTrue("The current visit stays in its window", window.any { it.token == at.current!!.token })
            val behind = window.first().token != at.history.first().token
            assertEquals("The behind visit is present exactly while the window reaches the oldest visit", cursor <= 64, behind)
            if (cursor == 0) assertEquals(at.previous, window.first())
            if (behind) assertEquals(at.history.first().token, window[1].token)
            assertEquals(window.size, window.map { it.token }.toSet().size)
        }
    }
}
