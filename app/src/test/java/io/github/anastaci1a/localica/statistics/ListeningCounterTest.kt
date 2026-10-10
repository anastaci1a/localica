package io.github.anastaci1a.localica.statistics

import org.junit.Assert.*
import org.junit.Test

class ListeningCounterTest {
    private val source = ListeningSource("entry", "song", "playlist")
    @Test fun pausesBufferingAndSeeksDoNotAddListeningTime() {
        val plays = mutableListOf<ListeningSource>()
        val counter = ListeningCounter(plays::add)
        counter.update(0, source, 180_000, true)
        counter.update(10_000, source, 180_000, false)
        counter.update(100_000, source, 180_000, true)
        // Seek jumps change media position, not this elapsed-realtime clock.
        counter.update(101_000, source, 180_000, true)
        counter.sample(119_999)
        assertTrue(plays.isEmpty())
        counter.sample(120_000)
        assertEquals(listOf(source), plays)
        counter.sample(300_000)
        assertEquals(1, plays.size)
    }
    @Test fun shortSongsUseHalfDurationAndUnknownDurationUsesThirtySeconds() {
        val plays = mutableListOf<ListeningSource>()
        val counter = ListeningCounter(plays::add)
        counter.update(0, source, 3001, true)
        counter.sample(1500); assertTrue(plays.isEmpty())
        counter.sample(1501); assertEquals(1, plays.size)
        counter.update(2000, source.copy(occurrenceId = "next"), null, true)
        counter.sample(31_999); assertEquals(1, plays.size)
        counter.sample(32_000); assertEquals(2, plays.size)
    }
    @Test fun durationDiscoveredAfterBufferingUsesAlreadyHeardTime() {
        val plays = mutableListOf<ListeningSource>()
        val counter = ListeningCounter(plays::add)
        counter.update(0, source, null, true)
        counter.update(4000, source, 6000, false)
        assertEquals(listOf(source), plays)
    }
    @Test fun repeatAndExplicitRestartEachBeginOneNewTraversal() {
        val plays = mutableListOf<ListeningSource>()
        val counter = ListeningCounter(plays::add)
        counter.update(0, source, 4000, true)
        counter.sample(2000)
        counter.update(4000, source, 4000, true, restart = true)
        counter.sample(5999); assertEquals(1, plays.size)
        counter.sample(6000); assertEquals(2, plays.size)
        counter.update(6500, source, 4000, false, restart = true)
        counter.sample(20_000); assertEquals(2, plays.size)
        counter.update(20_000, source, 4000, true)
        counter.sample(22_000); assertEquals(3, plays.size)
    }
    @Test fun partialTraversalsDoNotCombineAcrossOccurrencesAndOriginFollowsSavedSource() {
        val plays = mutableListOf<ListeningSource>()
        val counter = ListeningCounter(plays::add)
        counter.update(0, source, 10_000, true)
        val duplicate = source.copy(occurrenceId = "duplicate", playlistId = "another-playlist")
        counter.update(3000, duplicate, 10_000, true)
        counter.sample(7000); assertTrue(plays.isEmpty())
        counter.sample(8000); assertEquals(listOf(duplicate), plays)
        counter.update(9000, source, 10_000, true)
        counter.sample(14_000); assertEquals(listOf(duplicate, source), plays)
    }
    @Test fun transitionSettlesPreviousSongBeforeChangingItsDurationOrOrigin() {
        val plays = mutableListOf<ListeningSource>()
        val counter = ListeningCounter(plays::add)
        counter.update(0, source, 4000, true)
        counter.update(2100, source.copy(occurrenceId = "next", playlistId = null), 200_000, true)
        assertEquals(listOf(source), plays)
    }
}
