package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.*
import org.junit.Test

class MediaItemContourTest {
    @Test fun staticRowsAndTilesKeepTheEntireSelectionFootprint() {
        for (size in listOf(Size(340f, 48f), Size(340f, 80f), Size(72f, 128f), Size(20f, 40f))) {
            val bounds = mediaItemBounds(size)
            assertEquals(Rect(Offset.Zero, size), bounds)
            val contour = mediaItemContour(bounds, 16f)
            assertEquals(bounds, Rect(contour.left, contour.top, contour.right, contour.bottom))
            assertEquals(minOf(24f, size.minDimension / 2f), contour.topLeftCornerRadius.x, 0f)
            assertEquals(contour.topLeftCornerRadius, contour.bottomRightCornerRadius)
            assertEquals("Selection optics use the same circular corners", contour.topLeftCornerRadius.x,
                requireNotNull(simpleLensGeometry(listOf(SelectionRegion(bounds)), 16f)).corner, 0f)
        }
    }

    @Test fun pinchBoundsAndCornersFollowVisualSizeWithoutScalingTheRadius() {
        val measured = Size(340f, 120f)
        for (direction in LayoutDirection.entries) {
            for (visual in listOf(Size(72f, 128f), Size(180f, 80f), measured, Size(180f, 80f), Size(72f, 128f))) {
                val bounds = mediaItemBounds(measured, visual, direction)
                assertEquals(visual, bounds.size)
                assertEquals(0f, bounds.top, 0f)
                if (direction == LayoutDirection.Rtl) assertEquals(measured.width, bounds.right, 0f)
                else assertEquals(0f, bounds.left, 0f)
                assertEquals(24f, mediaItemContour(bounds, 16f).topLeftCornerRadius.x, 0f)
            }
        }
    }

    @Test fun cornersAreDensityAwareAndClampForSmallCells() {
        assertEquals(48f, mediaItemCornerRadius(Rect(0f, 0f, 680f, 120f), 32f), 0f)
        assertEquals(20f, mediaItemCornerRadius(Rect(0f, 0f, 40f, 120f), 32f), 0f)
        assertEquals(0f, mediaItemCornerRadius(Rect.Zero, 32f), 0f)
    }
}
