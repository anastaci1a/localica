package io.github.anastaci1a.localica.statistics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replays the observer samples produced by edit teardown and restoration on the base. */
class ListeningEditLifetimeTest {
    private val source = ListeningSource("entry", "song", "playlist")

    @Test fun partialListeningSurvivesEditWithoutCountingTheEditingPause() {
        val plays = mutableListOf<ListeningSource>()
        val counter = ListeningCounter(plays::add)
        counter.update(0, source, 180_000, true)
        counter.update(20_000, source, 180_000, false)
        counter.update(20_000, null, null, false)
        counter.update(120_000, source, 180_000, true)
        counter.sample(129_999)
        assertTrue(plays.isEmpty())
        counter.sample(130_000)
        assertEquals("20 seconds before edit plus 10 after must qualify", listOf(source), plays)
    }

    @Test fun qualifiedTraversalDoesNotCountAgainAfterEdit() {
        val plays = mutableListOf<ListeningSource>()
        val counter = ListeningCounter(plays::add)
        counter.update(0, source, 180_000, true)
        counter.update(30_000, source, 180_000, false)
        assertEquals(listOf(source), plays)
        counter.update(30_000, null, null, false)
        counter.update(130_000, source, 180_000, true)
        counter.sample(160_000)
        assertEquals("The restored traversal was already counted", listOf(source), plays)
    }

    @Test fun qualifiedTraversalDoesNotCountAgainAfterServiceRecreation() {
        val plays = mutableListOf<ListeningSource>()
        val original = ListeningCounter(plays::add)
        original.update(0, source, 180_000, true)
        original.update(30_000, source, 180_000, false)
        val recreated = ListeningCounter(plays::add)
        recreated.update(130_000, source, 180_000, true)
        recreated.sample(160_000)
        assertEquals("Recreating the observer must retain once-per-traversal accounting", listOf(source), plays)
    }
}
