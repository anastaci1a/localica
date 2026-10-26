package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMorphMotionTest {
    private val home = Rect(20f, 100f, 380f, 140f)
    private val raised = Rect(12f, 350f, 388f, 450f)

    @Test fun slotLookaheadAndActualMeasurementRequestTheirOwnMatchingAnchor() {
        val owner = SearchSlotLayout()
        assertEquals(12, owner.measure(true, .4f, 12, 80f).anchorOffset)
        assertEquals(null, owner.measure(true, .4f, 12, 80f).anchorOffset)
        assertEquals(12, owner.measure(false, .4f, 12, 80f).anchorOffset)
        assertEquals(null, owner.measure(false, .4f, 12, 80f).anchorOffset)
        owner.measure(true, .8f, 12, 80f)
        assertEquals(.8f, owner.fraction(true), 0f)
        assertEquals(.4f, owner.fraction(false), 0f)
    }

    @Test fun reducedSlotRestorationRequestsTheHeaderBeforeMeasuringItsFullHeight() {
        val owner = SearchSlotLayout()
        for (fraction in listOf(0f, 1f, 0f, 1f, 0f)) {
            for (lookahead in listOf(true, false)) {
                val measured = owner.measure(lookahead, fraction, 0, 98f)
                assertEquals(0, measured.anchorOffset)
                assertEquals(fraction, measured.fraction, 0f)
                assertEquals(fraction, owner.fraction(lookahead), 0f)
            }
        }
    }

    @Test fun slotMeasurementReversalsKeepTheOriginalOffsetAndExactOwnerFraction() {
        val owner = SearchSlotLayout()
        for (fraction in listOf(0f, .6f, .3f, .8f, 1f, .25f, 0f)) {
            val measured = owner.measure(false, fraction, 17, 81f)
            assertEquals(17, measured.anchorOffset)
            assertEquals(fraction, measured.fraction, 0f)
            assertEquals(null, owner.measure(false, fraction, 17, 81f).anchorOffset)
        }
    }

    @Test fun changingOwnedOffsetOrVacancyRequiresANewMeasurementAnchor() {
        val owner = SearchSlotLayout()
        assertEquals(0, owner.measure(false, .5f, 0, 98f).anchorOffset)
        assertEquals(14, owner.measure(false, .5f, 14, 98f).anchorOffset)
        assertEquals(14, owner.measure(false, .5f, 14, 84f).anchorOffset)
        assertEquals(null, owner.measure(false, .5f, 14, 84f).anchorOffset)
    }

    @Test fun scrolledOrUnownedSlotCannotRequestAnAnchorAndReacquisitionIsFresh() {
        val owner = SearchSlotLayout()
        for (lookahead in listOf(true, false)) {
            assertEquals(null, owner.measure(lookahead, .5f, -1, 98f).anchorOffset)
            assertEquals(null, owner.measure(lookahead, .5f, 0, 0f).anchorOffset)
            assertEquals(0, owner.measure(lookahead, .5f, 0, 98f).anchorOffset)
            assertEquals(null, owner.measure(lookahead, .5f, -1, 98f).anchorOffset)
            assertEquals(0, owner.measure(lookahead, .5f, 0, 98f).anchorOffset)
        }
    }

    @Test fun speculativeSlotPassCannotOverwriteTheActualPlacedFraction() {
        val owner = SearchSlotLayout()
        val actual = owner.measure(false, .6f, 0, 98f)
        owner.place(false, actual)
        val speculative = owner.measure(true, 0f, 0, 98f)
        owner.place(true, speculative)
        assertEquals(.6f, owner.placedFraction, 0f)
        val restored = owner.measure(false, 0f, 0, 98f)
        assertEquals(.6f, owner.placedFraction, 0f)
        owner.place(false, restored)
        assertEquals(0f, owner.placedFraction, 0f)
    }

    @Test fun slotPlacementUsesItsCapturedMeasurementRatherThanALaterRead() {
        val owner = SearchSlotLayout()
        val measured = owner.measure(false, .2f, 0, 98f)
        owner.measure(false, .8f, 0, 98f)
        owner.place(false, measured)
        assertEquals(.2f, owner.placedFraction, 0f)
        assertEquals(.8f, owner.fraction(false), 0f)
    }

    @Test fun slotMotionInvalidatesGridAndCachedHeaderBeforeTheirNextTraversal() {
        val motion = SearchSlotMotion()
        val token = motion.begin()
        val owner = SearchSlotLayout()
        val invalidated = mutableListOf<String>()
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            observer.observeReads("grid", { invalidated += it }) {
                owner.measure(false, motion.fraction, 0, 98f)
            }
            observer.observeReads("header", { invalidated += it }) {
                motion.fraction
                owner.fraction(false)
            }
            motion.settle(token, 1f)
            Snapshot.sendApplyNotifications()
            assertEquals(setOf("grid", "header"), invalidated.toSet())
            assertEquals(0f, owner.fraction(false), 0f)
            assertEquals(0, owner.measure(false, motion.fraction, 0, 98f).anchorOffset)
            assertEquals(1f, owner.fraction(false), 0f)
            invalidated.clear()
            Snapshot.sendApplyNotifications()
            assertTrue("Capturing a traversal does not enqueue another state mutation", invalidated.isEmpty())
        } finally { observer.stop(); observer.clear() }
    }

    @Test fun slotDoesNotMoveForShapeGrowthBeforePlacementOwnsTheBottom() {
        val owner = SearchSlotMotion()
        owner.begin()
        for (p in listOf(0f, .2f, .8f, 1f)) {
            assertTrue(SearchMorphFlight(home, 0f, true).openingRectangle(p, raised, null).height >= home.height)
            assertEquals(0f, owner.fraction, 0f)
        }
    }

    @Test fun slotReadsTheActualPlacementStateInBothTravelDirections() {
        for (end in listOf(450f, 50f)) {
            val owner = SearchSlotMotion()
            val token = owner.begin()
            var bottom = home.bottom
            val travel = SearchSlotTravel(0f, bottom, end)
            owner.bind(token) { travel.fraction(bottom) }
            for (step in 0..20) {
                val fraction = step / 20f
                bottom = home.bottom + (end - home.bottom) * fraction
                assertEquals(fraction, owner.fraction, .00001f)
            }
        }
    }

    @Test fun slotReturnAndInterruptedReopeningRetainTheirExactCurrentFraction() {
        val owner = SearchSlotMotion()
        owner.settle(owner.begin(), .6f)
        val closing = owner.begin()
        val flight = SearchMorphFlight(raised, .8f, false)
        var p = .8f
        owner.bind(closing) { .6f * (1f - flight.fraction(p)) }
        p = .4f
        assertEquals(.3f, owner.fraction, .00001f)
        val opening = owner.begin()
        var bottom = 300f
        val travel = SearchSlotTravel(owner.fraction, bottom, 500f)
        owner.bind(opening) { travel.fraction(bottom) }
        assertEquals(.3f, owner.fraction, .00001f)
        bottom = 400f
        assertEquals(.65f, owner.fraction, .00001f)
        owner.settle(closing, 0f)
        assertEquals(.65f, owner.fraction, .00001f)
    }

    @Test fun slotLateResizeRebasesWithoutChangingItsCurrentFraction() {
        val owner = SearchSlotMotion()
        val token = owner.begin()
        var bottom = 200f
        val first = SearchSlotTravel(0f, 100f, 500f)
        owner.bind(token) { first.fraction(bottom) }
        val next = SearchSlotTravel(owner.fraction, bottom, 350f)
        owner.bind(token) { next.fraction(bottom) }
        assertEquals(.25f, owner.fraction, 0f)
        bottom = 350f
        assertEquals(1f, owner.fraction, 0f)
    }

    @Test fun slotWindowReplacementRejectsOldWritersWithoutResettingTravel() {
        val owner = SearchSlotMotion()
        val old = owner.begin()
        var fraction = .4f
        owner.bind(old) { fraction }
        val replacement = owner.renew()
        owner.settle(old, 0f)
        owner.bind(old) { 0f }
        assertEquals(.4f, owner.fraction, 0f)
        fraction = .7f
        assertEquals(.7f, owner.fraction, 0f)
        owner.settle(replacement, 1f)
        assertEquals(1f, owner.fraction, 0f)
    }

    @Test fun zeroDistanceReducedAndOvershootingSlotEndpointsRemainBounded() {
        assertEquals(1f, SearchSlotTravel(.2f, 100f, 100f).fraction(100f), 0f)
        val owner = SearchSlotMotion()
        owner.settle(owner.begin(), 1f)
        assertEquals(1f, owner.fraction, 0f)
        owner.settle(owner.begin(), 0f)
        assertEquals(0f, owner.fraction, 0f)
        assertEquals(0f, SearchSlotTravel(0f, 100f, 500f).fraction(50f), 0f)
        assertEquals(1f, SearchSlotTravel(0f, 100f, 500f).fraction(550f), 0f)
    }

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
