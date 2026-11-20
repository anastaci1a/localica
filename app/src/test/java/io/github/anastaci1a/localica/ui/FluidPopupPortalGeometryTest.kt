package io.github.anastaci1a.localica.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FluidPopupPortalGeometryTest {
    private val backing = PopupPortalBounds(10.0, 20.0, 80.0, 50.0)
    private val path = PopupPortalBounds(8.0, 16.0, 40.0, 25.0)
    private val insets = PopupPortalInsets(top = 3.0, leading = 2.0, bottom = 5.0, trailing = 6.0)

    @Test fun apertureAndBoundsClippingHaveIndependentConditions() {
        assertTrue(fluidPopupClipsBounds(9))
        assertFalse(fluidPopupClipsBounds(12))
        assertNull(fluidPopupVisibleMask(null, roundedRect = false, equalsReferenceBounds = false))
        assertNull(fluidPopupVisibleMask(path, roundedRect = true, equalsReferenceBounds = true))
        assertEquals(path, fluidPopupVisibleMask(path, roundedRect = false, equalsReferenceBounds = true))
        assertEquals(path, fluidPopupVisibleMask(path, roundedRect = true, equalsReferenceBounds = false))
        assertEquals(-8.0 to -16.0, path.maskOffset())
    }

    @Test fun portalUsesVisibleExtentThenBackingThenDeclaredSize() {
        val visible = fluidPopupContentBounds(path, backing, null, insets, 1.5)
        val cropped = fluidPopupContentBounds(null, backing, path, insets, 1.5)
        val declared = fluidPopupContentBounds(null, null, path, insets, 2.0)
        assertEquals(PopupPortalBounds(0.0, 0.0, 60.0, 37.5), visible)
        assertEquals(PopupPortalBounds(0.0, 0.0, 108.0, 63.0), cropped)
        assertEquals(PopupPortalBounds(0.0, 0.0, 80.0, 50.0), declared)
        assertEquals(PopupPortalBounds(0.0, 0.0, 0.0, 0.0), fluidPopupContentBounds(null, null, null, insets, 2.0))
    }

    @Test fun cropStandardizesBeforeApplyingDirectionalInsets() {
        val original = PopupPortalBounds(20.0, 40.0, -14.0, -23.0)
        val crop = requireNotNull(insets.signedCrop(original))
        assertEquals(PopupPortalBounds(8.0, 20.0, 6.0, 15.0), crop)
        assertEquals(2.0 to 1.0, original.cropOffset(crop.midX, crop.midY))
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

    @Test fun menuPreviewOptionsToggleBoundsClipping() {
        assertTrue(fluidPopupClipsBounds(fluidPopupPreviewOptions(3, 8)))
        assertFalse(fluidPopupClipsBounds(fluidPopupPreviewOptions(4, 8)))
        assertEquals(12, fluidPopupPreviewOptions(5, 8))
        assertEquals(8, fluidPopupPreviewOptions(6, 8))
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
        val pivot = PopupPortalBounds(-4.5, 11.5, 4.0, 4.0)
        val targets = fluidGrowingOutlineTargets(
            larger, pivot, 4.0,
            toSmallerContainer = { square ->
                assertEquals(PopupPortalBounds(-5.0, 12.0, 4.0, 4.0), square)
                square.copy(x = square.x + 30.0, y = square.y + 20.0, width = 8.0)
            },
            toPivotContainer = { square ->
                assertEquals(PopupPortalBounds(1.0, 11.0, 4.0, 4.0), square)
                square.copy(x = square.x - 10.0, width = 6.0, height = 2.0)
            },
            companionInset = { bounds, inset ->
                assertTrue(bounds === larger)
                assertEquals(0.5, inset, 0.0)
                PopupPortalBounds(3.0, 2.0, 15.0, 15.0)
            },
        )
        val collapsed = FluidOutlineOverride(PopupPortalBounds(25.0, 32.0, 8.0, 4.0), 2.0, identityTransform = true)
        val companion = FluidOutlineOverride(PopupPortalBounds(-9.0, 11.0, 6.0, 2.0), 1.0)
        assertEquals(FluidOutlineStep(collapsed, null), targets.collapsedSource)
        assertEquals(FluidOutlineStep(FluidOutlineOverride(null, 20.0), companion), targets.overrounded)
        assertEquals(FluidOutlineStep(null, companion), targets.fullyExpanded)
        assertTrue(targets.overrounded.companion === targets.fullyExpanded.companion)
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
}
