package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchMorphFlightTest {
    private val home = Rect(20f, 80f, 380f, 128f)
    private val raised = Rect(12f, 320f, 388f, 384f)

    @Test fun closingStartsAtTheRenderedRectangleAndReturnsDirectlyHome() {
        val flight = SearchMorphFlight(raised, 1f, opening = false)
        assertEquals(raised, flight.rectangle(1f, home))
        assertEquals(Rect(16f, 200f, 384f, 256f), flight.rectangle(.5f, home))
        assertEquals(home, flight.rectangle(0f, home))
    }

    @Test fun interruptionKeepsItsActualRectangleInBothDirections() {
        val actual = Rect(16f, 200f, 384f, 256f)
        val close = SearchMorphFlight(actual, .4f, opening = false)
        assertEquals(actual, close.rectangle(.4f, home))
        assertEquals(home, close.rectangle(0f, home))
        val reopen = SearchMorphFlight(actual, .4f, opening = true)
        assertEquals(actual, reopen.rectangle(.4f, raised))
        assertEquals(raised, reopen.rectangle(1f, raised))
    }

    @Test fun endpointsAndOvershootRemainFiniteAndBounded() {
        assertEquals(home, SearchMorphFlight(raised, 0f, false).rectangle(0f, home))
        assertEquals(raised, SearchMorphFlight(home, 1f, true).rectangle(1f, raised))
        assertEquals(home, SearchMorphFlight(home, 0f, true).rectangle(-.1f, raised))
        assertEquals(raised, SearchMorphFlight(home, 0f, true).rectangle(1.1f, raised))
    }
}
