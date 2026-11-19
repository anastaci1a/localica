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
        assertNull(fluidPopupRasterScale(wantsMinification = false, displayScale = 2.5))
        assertEquals(2.5, requireNotNull(fluidPopupRasterScale(wantsMinification = true, displayScale = 2.5)), 0.0)
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
}
