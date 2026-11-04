package io.github.anastaci1a.localica.statistics

import org.junit.Assert.*
import org.junit.Test

class ListeningEditLifetimeTest {
    private val source = ListeningSource("entry", "song", "playlist")
    private class Fixture {
        var time = 0L
        var saved: ListeningIdentity? = null
        val plays = linkedMapOf<String, ListeningSource>()
        fun session() = ListeningSession({ time }, saved, { saved = it }, { source, id -> plays.putIfAbsent(id, source) })
    }

    @Test fun partialListeningSurvivesEditWithoutCountingTheEditingPause() {
        val f = Fixture()
        val session = f.session()
        session.update(source, "visit", 180_000, true)
        f.time = 20_000
        session.suspendForEdit()
        session.update(null, null, null, false)
        f.time = 120_000
        session.update(source, "visit", 180_000, true)
        session.finishEdit("visit")
        f.time = 129_999
        session.update(source, "visit", 180_000, true)
        assertTrue(f.plays.isEmpty())
        f.time = 130_000
        session.update(source, "visit", 180_000, true)
        assertEquals(listOf(source), f.plays.values.toList())
    }

    @Test fun qualifiedTraversalDoesNotCountAgainAfterEdit() {
        val f = Fixture()
        val session = f.session()
        session.update(source, "visit", 180_000, true)
        f.time = 30_000
        session.suspendForEdit()
        assertEquals(1, f.plays.size)
        val qualifiedIdentity = f.saved
        session.update(null, null, null, false)
        f.time = 130_000
        session.update(source, "visit", 180_000, true)
        session.finishEdit("visit")
        f.time = 160_000
        session.update(source, "visit", 180_000, true)
        assertEquals(qualifiedIdentity, f.saved)
        assertEquals(listOf(source), f.plays.values.toList())
    }

    @Test fun qualifiedTraversalDoesNotCountAgainAfterServiceRecreation() {
        val f = Fixture()
        val session = f.session()
        session.update(source, "visit", 180_000, true)
        f.time = 30_000
        session.detach()
        f.time = 130_000
        // A new observer attaches to this same app-owned session.
        session.update(source, "visit", 180_000, true)
        f.time = 160_000
        session.update(source, "visit", 180_000, true)
        assertEquals(listOf(source), f.plays.values.toList())
    }

    @Test fun processRecreationReusesEventIdentityButRealRestartGetsANewOne() {
        val f = Fixture()
        val original = f.session()
        original.update(source, "visit", 180_000, true)
        f.time = 30_000
        original.detach()
        val qualifiedIdentity = f.saved
        val recreated = f.session()
        f.time = 130_000
        recreated.update(source, "visit", 180_000, true)
        f.time = 160_000
        recreated.update(source, "visit", 180_000, true)
        assertEquals(qualifiedIdentity, f.saved)
        assertEquals(1, f.plays.size)
        recreated.update(source, "visit", 180_000, true, restart = true)
        f.time = 190_000
        recreated.update(source, "visit", 180_000, true)
        assertEquals(2, f.plays.size)
        assertNotEquals(qualifiedIdentity, f.saved)
    }

    @Test fun changedSelectionDuringEditDoesNotInheritPartialTimeEvenForTheSameOccurrence() {
        val f = Fixture()
        val session = f.session()
        session.update(source, "visit", 180_000, true)
        f.time = 20_000
        session.suspendForEdit()
        session.update(null, null, null, false)
        f.time = 120_000
        session.update(source, "selected-again", 180_000, true)
        session.finishEdit("selected-again")
        f.time = 130_000
        session.update(source, "selected-again", 180_000, true)
        assertTrue(f.plays.isEmpty())
        f.time = 150_000
        session.update(source, "selected-again", 180_000, true)
        assertEquals(1, f.plays.size)
    }

    @Test fun ordinaryNullEndsTraversalAndDoesNotPreservePartialTime() {
        val f = Fixture()
        val session = f.session()
        session.update(source, "visit", 180_000, true)
        f.time = 20_000
        session.update(null, null, null, false)
        assertNull(f.saved)
        f.time = 120_000
        session.update(source, "visit", 180_000, true)
        f.time = 130_000
        session.update(source, "visit", 180_000, true)
        assertTrue(f.plays.isEmpty())
    }

    @Test fun clearDuringEditDoesNotLeakItsCountedMarkerToALaterVisit() {
        val f = Fixture()
        val session = f.session()
        session.update(source, "visit", 180_000, true)
        f.time = 30_000
        session.suspendForEdit()
        session.update(null, null, null, false)
        session.finishEdit(null)
        assertNull(f.saved)
        f.time = 130_000
        session.update(source, "visit", 180_000, true)
        f.time = 160_000
        session.update(source, "visit", 180_000, true)
        assertEquals(2, f.plays.size)
    }
}
