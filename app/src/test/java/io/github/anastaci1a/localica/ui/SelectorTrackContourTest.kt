package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import org.junit.Assert.*
import org.junit.Test

class SelectorTrackContourTest {
    @Test fun uniformOutlineExactlyMatchesCurrentRoundRectAtEveryScale() {
        for (density in listOf(1f, 1.5f, 2.625f, 3f)) {
            for (size in listOf(Offset(48f, 64f), Offset(360f, 56f), Offset(56f, 360f))) {
                val bounds = Rect(7f * density, -3f * density,
                    (7f + size.x) * density, (-3f + size.y) * density)
                for (corner in listOf(0f, 20f * density, 1000f)) {
                    val contour = SelectorTrackContour.uniform(bounds, corner)
                    val radius = CornerRadius(corner.coerceAtMost(minOf(bounds.width, bounds.height) / 2f))
                    assertEquals(bounds, contour.bounds)
                    assertEquals(RoundRect(bounds, radius), contour.outline)
                    assertEquals(radius, contour.outline.topLeftCornerRadius)
                    assertEquals(radius, contour.outline.topRightCornerRadius)
                    assertEquals(radius, contour.outline.bottomRightCornerRadius)
                    assertEquals(radius, contour.outline.bottomLeftCornerRadius)
                }
            }
        }
    }

    @Test fun uniformContainmentMatchesExistingSelectorHitGeometry() {
        for (corner in listOf(0f, 8f, 28f, 100f)) {
            val bounds = Rect(-4f, 6f, 76f, 62f)
            val frame = SelectorTrackLens(bounds, corner, 1f, 0f, 32f, 56f)
            val viewport = Rect(-100f, -100f, 200f, 200f)
            for (y in 4..64) for (x in -6..78) {
                val point = Offset(x.toFloat(), y.toFloat())
                assertEquals("corner=$corner at $point", selectorVisibleLensContains(point, frame, 0f, viewport),
                    frame.contour().contains(point))
            }
        }
    }

    @Test fun translatedContoursPreserveAllCornersAndPhysicalPointOwnership() {
        val contour = SelectorTrackContour.uniform(Rect(0f, 0f, 80f, 56f), 20f)
        for (offset in listOf(Offset(-4f, 0f), Offset(0f, -4f), Offset(120f, 36f))) {
            val moved = contour.translated(offset)
            assertEquals(contour.bounds.translate(offset), moved.bounds)
            assertEquals(contour.outline.topLeftCornerRadius, moved.outline.topLeftCornerRadius)
            assertEquals(contour.outline.bottomRightCornerRadius, moved.outline.bottomRightCornerRadius)
            for (point in listOf(Offset.Zero, Offset(20f, 0f), Offset(40f, 28f), Offset(80f, 28f))) {
                assertEquals(contour.contains(point), moved.contains(point + offset))
            }
        }
    }

    @Test fun bothAxesAndRtlRetainTheDisplayedTrackFrameWithoutNewDeformation() {
        for (vertical in listOf(false, true)) for (rtl in listOf(false, true))
            for (wall in listOf(-1f, 1f)) for (pull in listOf(0f, .02f, .04f)) {
                val geometry = selectorTrackGeometry(360f, 64f, pull, wall, vertical, rtl,
                    reducedMotion = false, overflow = false, heldCrossInset = 4f)
                val frame = geometry.lens(if (wall < 0f) 0f else 2f, 3,
                    SelectorTrackInsets(0f, 4f, 4f), vertical, rtl, GlassShape(-.12f, -.2f),
                    geometry.crossExtent / 2f)
                val contour = frame.contour()
                assertEquals(frame.bounds, contour.bounds)
                assertEquals(RoundRect(frame.bounds, CornerRadius(frame.corner)), contour.outline)
                assertTrue(contour.contains(frame.bounds.center))
            }
    }
    @Test fun copyAndTranslationCannotBypassTheUniformFiniteContract() {
        val contour = SelectorTrackContour.uniform(Rect(0f, 0f, 80f, 56f), 20f)
        for (corner in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f)) {
            assertThrows(IllegalArgumentException::class.java) {
                SelectorTrackContour.uniform(contour.bounds, corner)
            }
        }
        for (point in listOf(Offset(Float.NaN, 20f), Offset(20f, Float.POSITIVE_INFINITY))) {
            assertFalse(contour.contains(point))
        }
        assertThrows(IllegalArgumentException::class.java) {
            contour.copy(outline = RoundRect(Rect(0f, 0f, 80f, 56f), CornerRadius(20f, 10f)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            contour.copy(outline = RoundRect(Rect(0f, 0f, 80f, 56f), CornerRadius(100f)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            contour.translated(Offset(Float.MAX_VALUE, 0f))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SelectorTrackContour.uniform(Rect(-Float.MAX_VALUE, 0f, Float.MAX_VALUE, 56f), 20f)
        }
    }

}
