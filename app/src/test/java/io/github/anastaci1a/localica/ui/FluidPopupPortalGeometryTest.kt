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

        assertEquals(PopupPortalPlacement(backing, 42.0, 29.0), fluidPopupPortalPlacement(visible, backing, path))
        assertEquals(PopupPortalPlacement(visible, 30.0, 18.75), fluidPopupPortalPlacement(visible, null, path))
        assertEquals(PopupPortalPlacement(cropped, 54.0, 31.5), fluidPopupPortalPlacement(cropped, backing, null))
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

    @Test fun liveApertureUsesLocalInsetsAndAxisSelectedRatios() {
        val aperture = fluidPopupAperture(200.0, 120.0, insets)
        assertEquals(PopupPortalBounds(0.0, 0.0, 208.0, 128.0), aperture)
        assertEquals(104.0 to 64.0, aperture.midX to aperture.midY)
        assertEquals(104.0 to -6.0, aperture.destinationPosition(insets, insets.copy(top = 9.0)))

        assertEquals(2.0 to 3.0, fluidPopupDestinationScale(200.0, 120.0, 100.0, 40.0, axis = 0))
        assertEquals(2.0 to 2.0, fluidPopupDestinationScale(200.0, 120.0, 100.0, 40.0, axis = 1))
        assertEquals(3.0 to 3.0, fluidPopupDestinationScale(200.0, 120.0, 100.0, 40.0, axis = 2))
        assertEquals(1.0 to 3.0, fluidPopupDestinationScale(200.0, 120.0, -0.0, 40.0, axis = 0))
        assertEquals(1.0 to 1.0, fluidPopupDestinationScale(200.0, 120.0, 100.0, 0.0, axis = 2))
    }

    @Test fun autoAxisUsesRelativeChangeAndItsOrderedTieRule() {
        assertEquals(2, fluidPopupAutoAxis(80.0, 40.0, 120.0, 44.0))
        assertEquals(1, fluidPopupAutoAxis(80.0, 40.0, 88.0, 60.0))
        assertEquals(1, fluidPopupAutoAxis(80.0, 40.0, 120.0, 60.0))
        assertEquals(1, fluidPopupAutoAxis(80.0, 0.0, 120.0, 0.0))
    }

    @Test fun menuPreviewKeepsRolesDirectionAndScaleTolerance() {
        assertEquals(insets, fluidPopupPreviewInsets(3.0, 2.0, 5.0, 6.0, reversed = false))
        assertEquals(insets, fluidPopupPreviewInsets(3.0, 6.0, 5.0, 2.0, reversed = true))
        assertTrue(fluidPopupClipsBounds(fluidPopupPreviewOptions(3, 8)))
        assertFalse(fluidPopupClipsBounds(fluidPopupPreviewOptions(4, 8)))
        assertEquals(12, fluidPopupPreviewOptions(5, 8))
        assertEquals(8, fluidPopupPreviewOptions(6, 8))
        assertFalse(fluidPopupPreviewScaleChanges(1.0, Math.nextUp(1.0)))
        assertTrue(fluidPopupPreviewScaleChanges(1.0, Math.nextUp(Math.nextUp(1.0))))
        assertTrue(fluidPopupPreviewScaleChanges(1.0, Double.NaN))
    }

    @Test fun throughRoleRequestsKeepTheRecoveredIndexOrder() {
        assertEquals(PopupThroughItem.Pivot to PopupThroughItem.From, popupThroughSlots(growing = true, fromPhase = true))
        assertEquals(PopupThroughItem.To to PopupThroughItem.Pivot, popupThroughSlots(growing = true, fromPhase = false))
        assertEquals(PopupThroughItem.From to PopupThroughItem.Pivot, popupThroughSlots(growing = false, fromPhase = true))
        assertEquals(PopupThroughItem.Pivot to PopupThroughItem.To, popupThroughSlots(growing = false, fromPhase = false))
    }
}
