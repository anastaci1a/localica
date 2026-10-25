package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMorphMotionTest {
    private val home = Rect(20f, 100f, 380f, 140f)
    private val raised = Rect(12f, 350f, 388f, 450f)

    @Test fun inheritedImeCannotStartTravelBeforeThePopupHasItsOwnNativeInsets() {
        assertFalse(searchOpeningOwnsPosition(false, 600, 0, true, true, false))
        assertFalse(searchOpeningOwnsPosition(false, 600, 600, false, true, false))
        assertFalse(searchOpeningOwnsPosition(false, 0, 600, true, true, false))
        assertTrue(searchOpeningOwnsPosition(false, 600, 600, true, true, false))
    }

    @Test fun startedPositionCannotLoseItsOwnerWhenAnImeDisappearsBeforeFallback() {
        // The old trigger-only condition cancelled travel, while positioned=true
        // cancelled the fallback too, permanently pinning the opening at its pill.
        assertTrue(searchOpeningOwnsPosition(true, 0, 0, true, true, false))
        assertTrue(searchOpeningOwnsPosition(true, 0, 0, false, null, false))
        assertTrue(searchOpeningOwnsPosition(true, 100, 600, true, true, false))
    }

    @Test fun absentKeyboardFallbackAndNonEditingResultsStillOwnTheirPlacement() {
        assertFalse(searchOpeningOwnsPosition(false, 0, 0, true, true, false))
        assertTrue(searchOpeningOwnsPosition(false, 0, 0, true, true, true))
        assertTrue(searchOpeningOwnsPosition(false, 0, 0, true, false, false))
    }

    @Test fun tallForegroundTargetsTheFinalKeyboardBottomThroughoutItsArrival() {
        for (ime in listOf(100, 200, 400, 500)) {
            val target = searchPanelTarget(Offset.Zero, 400f, 1000f - ime,
                376f, 700f, 12f, ime, 500)
            assertEquals(Rect(12f, 12f, 388f, 488f), target)
        }
    }

    @Test fun keyboardArrivalBelowNavigationInsetDoesNotOverSubtractSafePadding() {
        for (ime in listOf(1, 10, 47, 48, 100, 500)) {
            val target = searchPanelTarget(Offset.Zero, 400f, 1000f - maxOf(ime, 48),
                376f, 700f, 12f, ime, 500, safeBottom = 48)
            assertEquals(Rect(12f, 12f, 388f, 488f), target)
        }
    }

    @Test fun absentKeyboardCentersWithinItsSafeHeightWithoutBlockingShape() {
        val target = searchPanelTarget(Offset(0f, 24f), 400f, 1000f,
            376f, 100f, 12f, 0, 0)
        assertEquals(Rect(12f, 474f, 388f, 574f), target)
    }

    @Test fun capturedRectangleInterpolationKeepsAllFourEdgesTogether() {
        val flight = SearchMorphFlight(home, 0f, opening = true)
        assertEquals(home, flight.rectangle(0f, raised))
        assertEquals(Rect(16f, 225f, 384f, 295f), flight.rectangle(.5f, raised))
        assertEquals(raised, flight.rectangle(1f, raised))
    }

    @Test fun openingGrowsDownBeforeKeyboardPlacementStarts() {
        val flight = SearchMorphFlight(home, 0f, opening = true)
        assertEquals(home, flight.openingRectangle(0f, raised, null))
        assertEquals(Rect(16f, 100f, 384f, 170f), flight.openingRectangle(.5f, raised, null))
        assertEquals(Rect(12f, 100f, 388f, 200f), flight.openingRectangle(1f, raised, null))
    }

    @Test fun keyboardTravelStartsContinuouslyWhileShapeIsStillGrowing() {
        val flight = SearchMorphFlight(home, 0f, opening = true)
        val before = flight.openingRectangle(.4f, raised, null)
        assertEquals(before, flight.openingRectangle(.4f, raised, before.bottom))
        val moving = flight.openingRectangle(.5f, raised, before.bottom + 35f)
        assertEquals(before.bottom + 35f, moving.bottom, .001f)
        assertEquals(70f, moving.height, .001f)
    }

    @Test fun shapeFinishesUpwardOnceItsBottomMeetsTheKeyboard() {
        val flight = SearchMorphFlight(home, 0f, opening = true)
        val half = flight.openingRectangle(.5f, raised, raised.bottom)
        val complete = flight.openingRectangle(1f, raised, raised.bottom)
        assertEquals(raised.bottom, half.bottom, 0f)
        assertEquals(Rect(16f, 380f, 384f, 450f), half)
        assertEquals(raised, complete)
    }

    @Test fun cancelAndReverseDuringIndependentTravelCaptureTheDisplayedRectangle() {
        val opening = SearchMorphFlight(home, 0f, true)
        val displayed = opening.openingRectangle(.4f, raised, 250f)
        val returning = SearchMorphFlight(displayed, .4f, false)
        assertEquals(displayed, returning.rectangle(.4f, home))
        val partialReturn = returning.rectangle(.2f, home)
        val reopened = SearchMorphFlight(partialReturn, .2f, true)
        assertEquals(partialReturn, reopened.openingRectangle(.2f, raised, null))
        assertEquals(partialReturn, reopened.openingRectangle(.2f, raised, partialReturn.bottom))
        assertEquals(raised, reopened.openingRectangle(1f, raised, raised.bottom))
    }

    @Test fun shrinkingKeyboardViewportClampsHeightWithoutDetachingTheOwnedBottom() {
        val flight = SearchMorphFlight(home, 0f, true)
        val tall = Rect(12f, 0f, 388f, 600f)
        val displayed = flight.openingRectangle(.8f, tall, 400f, maxHeight = 300f)
        assertEquals(300f, displayed.height, 0f)
        assertEquals(400f, displayed.bottom, 0f)
    }

    @Test fun completedShapeCanContinueResizingUpwardWithoutMovingTheKeyboardBottom() {
        val flight = SearchMorphFlight(home, 0f, true)
        for (height in listOf(100f, 120f, 150f)) {
            val target = Rect(raised.left, 0f, raised.right, height)
            val displayed = flight.openingRectangle(1f, target, raised.bottom)
            assertEquals(height, displayed.height, .001f)
            assertEquals(raised.bottom, displayed.bottom, .001f)
        }
    }

    @Test fun contentResizeRebasesWithoutMovingTheOwnedBottom() {
        val displayed = SearchMorphFlight(home, 0f, true).openingRectangle(.4f, raised, 260f)
        val taller = raised.copy(top = raised.top - 50f)
        val rebase = SearchMorphFlight(displayed, .4f, true)
        assertEquals(displayed, rebase.openingRectangle(.4f, taller, displayed.bottom))
        assertEquals(taller, rebase.openingRectangle(1f, taller, taller.bottom))
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
