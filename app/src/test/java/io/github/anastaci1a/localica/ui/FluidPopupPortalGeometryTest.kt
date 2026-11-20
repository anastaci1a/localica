package io.github.anastaci1a.localica.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FluidPopupPortalGeometryTest {
    private val insets = PopupPortalInsets(top = 3.0, leading = 2.0, bottom = 5.0, trailing = 6.0)

    @Test fun cropStandardizesBeforeApplyingDirectionalInsets() {
        val original = PopupPortalBounds(20.0, 40.0, -14.0, -23.0)
        val crop = requireNotNull(insets.signedCrop(original))
        assertEquals(PopupPortalBounds(8.0, 20.0, 6.0, 15.0), crop)
        assertNull(insets.signedCrop(null))
        assertEquals(0.0, requireNotNull(insets.copy(trailing = 12.0).signedCrop(original)).width, 0.0)
        assertEquals(-1.0, requireNotNull(insets.copy(trailing = 13.0).signedCrop(original)).width, 0.0)
    }

    @Test fun endpointSelectionUsesScaledAreaAndKeepsTheSourceOnTies() {
        val from = PopupPortalBounds(0.0, 0.0, 80.0, 20.0)
        val to = PopupPortalBounds(0.0, 0.0, 20.0, 80.0)
        assertFalse(fluidPopupContentGrows(from, to))
        assertFalse(fluidPopupContentGrows(from, to.copy(height = 79.9)))
        assertTrue(fluidPopupContentGrows(from, to.copy(height = 80.1)))
        assertFalse(fluidPopupContentGrows(from, to.copy(height = Double.NaN)))
        assertEquals(PopupPortalBounds(60.0, 190.0, 80.0, 20.0), from.centeredAt(100.0, 200.0))
    }

    @Test fun throughRoleRequestsKeepTheRecoveredIndexOrder() {
        val smaller = PopupPortalBounds(0.0, 0.0, 40.0, 20.0)
        val larger = PopupPortalBounds(0.0, 0.0, 50.0, 30.0)
        val tied = PopupPortalBounds(0.0, 0.0, 20.0, 40.0)
        assertEquals(PopupThroughItem.Pivot to PopupThroughItem.From, popupThroughSlots(smaller, larger, fromPhase = true))
        assertEquals(PopupThroughItem.To to PopupThroughItem.Pivot, popupThroughSlots(smaller, larger, fromPhase = false))
        assertEquals(PopupThroughItem.From to PopupThroughItem.Pivot, popupThroughSlots(larger, smaller, fromPhase = true))
        assertEquals(PopupThroughItem.Pivot to PopupThroughItem.To, popupThroughSlots(larger, smaller, fromPhase = false))
        assertEquals(PopupThroughItem.From to PopupThroughItem.Pivot, popupThroughSlots(smaller, tied, fromPhase = true))
        assertEquals(PopupThroughItem.Pivot to PopupThroughItem.To, popupThroughSlots(smaller, tied, fromPhase = false))
    }

    @Test fun growingOutlineTargetsRoundBeforeConversionAndKeepTheCompanionSeparate() {
        val larger = PopupPortalBounds(0.0, 0.0, 20.0, 20.0)
        val pivot = PopupPortalBounds(-8.5, 11.5, 4.0, 4.0)
        val inPivotContainer = mutableListOf<PopupPortalBounds>()
        val targets = fluidOutlineTargets(
            pivot, larger, pivot,
            toSmallerContainer = { square ->
                assertEquals(PopupPortalBounds(-9.0, 12.0, 4.0, 4.0), square)
                square.copy(x = square.x + 30.0, y = square.y + 20.0, width = 8.0)
            },
            toPivotContainer = { square ->
                inPivotContainer.add(square)
                square.copy(x = square.x - 10.0, width = 6.0, height = 2.0)
            },
        )
        assertEquals(listOf(PopupPortalBounds(-2.0, 11.0, 4.0, 4.0),
            PopupPortalBounds(-17.0, 4.0, 20.0, 20.0)), inPivotContainer)
        assertEquals(FluidOutlineOverride(PopupPortalBounds(21.0, 32.0, 8.0, 4.0), 2.0), targets.collapsedSource)
        assertEquals(FluidOutlineOverride(PopupPortalBounds(-12.0, 11.0, 6.0, 2.0), 1.0, 0.25), targets.companion)
        assertEquals(FluidOutlineOverride(PopupPortalBounds(-27.0, 4.0, 6.0, 2.0), 1.0, 0.1), targets.sourcePivot)
        assertEquals(FluidOutlineOverride(null, 20.0), targets.overrounded)
    }

    @Test fun growingKickUsesTheLargerAnchorOrThePivotInTheSameRoot() {
        val larger = PopupPortalBounds(0.0, 100.0, 90.0, 200.0)
        val pivot = PopupPortalBounds(0.0, 140.0, 45.0, 20.0)
        assertEquals(40.0, larger.growingKickTarget(pivot, 0.5, null), 0.0)
        assertEquals(-40.0, larger.growingKickTarget(pivot, 0.75, null), 0.0)
        assertEquals(60.0, larger.growingKickTarget(pivot, 0.75, 0.125), 0.0)
        assertEquals(-20.0, larger.copy(height = 60.0).growingKickTarget(pivot, 0.75, null), 0.0)
        assertEquals(-30.0, larger.copy(height = 165.0).growingKickTarget(pivot, 0.75, null), 0.0)
        assertEquals(-40.0, larger.copy(height = Double.NaN).growingKickTarget(pivot, 0.75, null), 0.0)
        assertEquals(-80.0, larger.growingKickTarget(pivot.copy(y = Double.NaN), 0.5, null), 0.0)
        assertEquals((-0.0).toRawBits(), larger.growingKickTarget(pivot.copy(y = 190.0), 0.5, null).toRawBits())
    }

    @Test fun invertedSourceInsetStillSetsThePivotSizeRadiusAndScale() {
        val smaller = PopupPortalBounds(0.0, 0.0, 40.0, 20.0)
        val pivot = PopupPortalBounds(0.0, 0.0, 88.0, 44.0)
        val larger = PopupPortalBounds(0.0, 0.0, 250.0, 360.0)
        val targets = fluidOutlineTargets(smaller, larger, pivot, { it }, { it })
        val source = requireNotNull(targets.sourcePivot)
        val bounds = requireNotNull(source.bounds)
        assertEquals(Double.POSITIVE_INFINITY, bounds.x, 0.0)
        assertEquals(Double.POSITIVE_INFINITY, bounds.y, 0.0)
        assertEquals(250.0, bounds.width, 0.0)
        assertEquals(250.0, bounds.height, 0.0)
        assertEquals(125.0, source.cornerRadius, 0.0)
        assertEquals(22.0 / 360.0, source.scale, 0.0)
        assertEquals(PopupPortalBounds(34.0, 12.0, 20.0, 20.0), targets.collapsedSource.bounds)
    }

    @Test fun emptySourceInsetClampsThePivotToItsOnlyPoint() {
        val smaller = PopupPortalBounds(10.0, 20.0, 40.0, 20.0)
        val pivot = PopupPortalBounds(Double.NaN, 60.0, 40.0, 20.0)
        val larger = PopupPortalBounds(0.0, 0.0, 250.0, 360.0)
        val source = requireNotNull(fluidOutlineTargets(smaller, larger, pivot, { it }, { it }).sourcePivot)
        assertEquals(PopupPortalBounds(-95.0, -95.0, 250.0, 250.0), source.bounds)
    }
}
