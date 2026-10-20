package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchMorphMotionTest {
    private val home = Rect(20f, 100f, 380f, 140f)
    private val raised = Rect(12f, 350f, 388f, 450f)

    @Test fun openingUsesOneFixedRectangleForAllFourEdges() {
        val flight = SearchMorphFlight(home, 0f, opening = true)
        assertEquals(home, flight.rectangle(0f, raised))
        assertEquals(Rect(16f, 225f, 384f, 295f), flight.rectangle(.5f, raised))
        assertEquals(raised, flight.rectangle(1f, raised))
    }

    @Test fun returningWindowRectangleIsIndependentOfChangingImeViewport() {
        val flight = SearchMorphFlight(raised, 1f, opening = false)
        for (originY in listOf(0f, 24f, 180f, 320f)) {
            val origin = Offset(0f, originY)
            val local = flight.rectangle(.5f, home).translate(-origin)
            assertEquals(Rect(16f, 225f, 384f, 295f), local.translate(origin))
        }
    }

    @Test fun interruptedOpeningReturnsFromItsDisplayedSizeAndPosition() {
        val displayed = SearchMorphFlight(home, 0f, true).rectangle(.4f, raised)
        val returning = SearchMorphFlight(displayed, .4f, false)
        assertEquals(displayed, returning.rectangle(.4f, home))
        assertEquals(SearchMorphFlight(home, 0f, true).rectangle(.2f, raised), returning.rectangle(.2f, home))
        assertEquals(home, returning.rectangle(0f, home))
    }

    @Test fun reopeningRetargetsCurrentGeometryIncludingChangedKeyboardSize() {
        val displayed = SearchMorphFlight(raised, 1f, false).rectangle(.3f, home)
        val newKeyboardTarget = Rect(12f, 280f, 388f, 380f)
        val reopening = SearchMorphFlight(displayed, .3f, true)
        assertEquals(displayed, reopening.rectangle(.3f, newKeyboardTarget))
        assertEquals(newKeyboardTarget, reopening.rectangle(1f, newKeyboardTarget))
    }

    @Test fun homeDestinationTracksRestoredListAnchorWithoutChangingCloseStart() {
        val flight = SearchMorphFlight(raised, 1f, false)
        val restoredHome = home.translate(Offset(0f, 18f))
        assertEquals(raised, flight.rectangle(1f, restoredHome))
        assertEquals(restoredHome, flight.rectangle(0f, restoredHome))
    }

    @Test fun finishedAndOvershootingProgressHaveFiniteBoundedEndpoints() {
        assertEquals(home, SearchMorphFlight(home, 0f, false).rectangle(0f, home))
        assertEquals(raised, SearchMorphFlight(raised, 1f, true).rectangle(1f, raised))
        assertEquals(home, SearchMorphFlight(home, 0f, true).rectangle(-.1f, raised))
        assertEquals(raised, SearchMorphFlight(home, 0f, true).rectangle(1.1f, raised))
    }
}
