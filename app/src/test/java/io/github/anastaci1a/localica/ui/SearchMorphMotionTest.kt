package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
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

    @Test fun openingUsesReportedDestinationBeforeCurrentImeMoves() {
        val target = searchPanelTarget(Offset.Zero, 400f, 952f,
            376f, 100f, 12f, 0, 500, safeBottom = 48, opening = true)
        assertEquals(Rect(12f, 388f, 388f, 488f), target)
    }

    @Test fun unavailableOpeningTargetStartsAtSafeBottomThenTracksLiveIme() {
        for (ime in listOf(0, 20, 48, 200, 500)) {
            val target = searchPanelTarget(Offset.Zero, 400f, 1000f - maxOf(ime, 48),
                376f, 100f, 12f, ime, ime, safeBottom = 48, opening = true)
            assertEquals(1000f - maxOf(ime, 48) - 12f, target.bottom, 0f)
            assertEquals(100f, target.height, 0f)
        }
    }

    @Test fun openingTargetAndCurrentInsetsShareOneSafeViewport() {
        for (ime in listOf(0, 20, 48, 200, 500)) {
            val target = searchPanelTarget(Offset(0f, 24f), 400f, 1000f - maxOf(ime, 48),
                376f, 700f, 12f, ime, 500, safeBottom = 48, opening = true)
            assertEquals(Rect(12f, 36f, 388f, 512f), target)
        }
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
}
