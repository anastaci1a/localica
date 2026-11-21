package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.ui.spec.PopupPortalBounds
import io.github.anastaci1a.localica.ui.spec.PopupPortalInsets
import io.github.anastaci1a.localica.ui.spec.fluidDestinationPlacement
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

    @Test fun destinationPlacementUsesTheApertureAndDestinationInsetsPerAxis() {
        val apertureInsets = PopupPortalInsets(4.0, 6.0, 8.0, 12.0)
        val item = PopupPortalBounds(0.0, 0.0, 52.0, 50.0)
        val destinationInsets = PopupPortalInsets(6.0, 3.0, 4.0, 9.0)

        val horizontal = fluidDestinationPlacement(80.0, 40.0, apertureInsets, item, destinationInsets, axis = 1)
        assertEquals(2.0, horizontal.scaleX, 0.0)
        assertEquals(2.0, horizontal.scaleY, 0.0)
        assertEquals(PopupPortalBounds(52.0, -8.0, 52.0, 26.0), horizontal.layer)

        val vertical = fluidDestinationPlacement(80.0, 40.0, apertureInsets, item, destinationInsets, axis = 2)
        assertEquals(1.0, vertical.scaleX, 0.0)
        assertEquals(1.0, vertical.scaleY, 0.0)
        assertEquals(PopupPortalBounds(49.0, 0.0, 98.0, 50.0), vertical.layer)

        val independent = fluidDestinationPlacement(80.0, 40.0, apertureInsets, item, destinationInsets, axis = 0)
        assertEquals(2.0, independent.scaleX, 0.0)
        assertEquals(1.0, independent.scaleY, 0.0)
        assertEquals(PopupPortalBounds(49.0, 0.0, 52.0, 50.0), independent.layer)

        val emptyWidth = fluidDestinationPlacement(80.0, 30.0, apertureInsets, item.copy(width = 12.0), destinationInsets, axis = 0)
        assertEquals(1.0, emptyWidth.scaleX, 0.0)
        assertEquals(0.75, emptyWidth.scaleY, 0.0)
        val rightToLeft = fluidDestinationPlacement(80.0, 40.0, apertureInsets, item, destinationInsets, axis = 1, leftToRight = false)
        assertEquals(PopupPortalBounds(46.0, -8.0, 52.0, 26.0), rightToLeft.layer)
        val zeroSpan = fluidDestinationPlacement(0.0, 40.0, apertureInsets, item, destinationInsets, axis = 1)
        assertTrue(zeroSpan.layer.x.isNaN())
        assertEquals(Double.POSITIVE_INFINITY, zeroSpan.layer.height, 0.0)
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

    @Test fun companionDistinguishesAnInvertedInsetFromAZeroExtent() {
        val source = PopupPortalBounds(0.0, 0.0, 44.0, 44.0)
        val narrow = PopupPortalBounds(100.0, 10.0, 5.0, 500.0)
        val inverted = fluidOutlineTargets(source, narrow, source, { it }, { square ->
            assertTrue(square.x.isFinite() && square.y.isFinite())
            square
        }).companion
        val retained = PopupPortalBounds(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 44.0, 44.0)
        assertEquals(FluidOutlineOverride(retained, 22.0, 0.25), inverted)

        val zeroExtent = narrow.copy(width = 11.0, height = 200.0)
        val pinned = fluidOutlineTargets(source, zeroExtent, source, { it }, { it }).companion
        assertEquals(FluidOutlineOverride(PopupPortalBounds(84.0, 22.0, 44.0, 44.0), 22.0, 0.25), pinned)
    }
}
