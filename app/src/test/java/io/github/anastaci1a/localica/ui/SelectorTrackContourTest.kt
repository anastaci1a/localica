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
                val expected = previousVisibleContains(point, frame, 0f, viewport)
                assertEquals("corner=$corner at $point", expected, frame.contour().contains(point))
                assertEquals(expected, selectorVisibleLensContains(point, frame, 0f, viewport))
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

    @Test fun fractionalArcBoundariesAndScrollTransformsKeepPreviousHitDecisionsExactly() {
        for (density in listOf(1f, 1.5f, 2.625f, 3f)) for (rtl in listOf(false, true)) {
            val bounds = Rect(7.125f * density, -3.25f * density, 87.125f * density, 52.75f * density)
            val frame = SelectorTrackLens(bounds, 20f * density, if (rtl) -1f else 1f, 0f, 32f * density, bounds.height)
            val scroll = selectorContentScrollOffset(17, 39, rtl)
            val viewport = Rect(-20f, -20f, 300f, 300f)
            val centers = listOf(Offset(bounds.left + frame.corner, bounds.top + frame.corner),
                Offset(bounds.right - frame.corner, bounds.bottom - frame.corner))
            val samples = centers.flatMap { center -> (0..360 step 3).flatMap { degrees ->
                val radians = Math.toRadians(degrees.toDouble())
                val edge = center + Offset(kotlin.math.cos(radians).toFloat(), kotlin.math.sin(radians).toFloat()) * frame.corner
                listOf(edge, edge.copy(x = java.lang.Math.nextDown(edge.x)), edge.copy(x = java.lang.Math.nextUp(edge.x)))
            } } + listOf(bounds.topLeft, bounds.bottomRight, Offset(bounds.right, bounds.center.y),
                Offset(bounds.center.x, bounds.bottom), Offset(Float.NaN, 0f), Offset(0f, Float.POSITIVE_INFINITY))
            for (content in samples) {
                val point = content.copy(x = content.x - scroll)
                assertEquals("density=$density rtl=$rtl point=$point", previousVisibleContains(point, frame, scroll, viewport),
                    selectorVisibleLensContains(point, frame, scroll, viewport))
            }
        }
    }

    @Test fun normalizedUniformContourKeepsTheExistingScalarOpticalField() {
        val bounds = Rect(17.25f, 3.75f, 73.125f, 59.625f)
        for (radius in listOf(0f, 20f, bounds.width / 2f, java.lang.Math.nextUp(bounds.width / 2f))) {
            val contour = SelectorTrackContour.uniform(bounds, radius)
            val previous = LensGeometry(bounds, radius)
            val current = LensGeometry(contour.bounds, contour.corner)
            for (y in 0..16) for (x in 0..16) {
                val point = Offset(bounds.left + bounds.width * x / 16f, bounds.top + bounds.height * y / 16f)
                assertEquals(previous.field(point), current.field(point))
            }
        }
    }

    /** Frozen pre-adapter implementation, deliberately independent of the contour helper. */
    private fun previousVisibleContains(point: Offset, lens: SelectorTrackLens, contentOffset: Float, viewport: Rect): Boolean {
        if (!viewport.contains(point)) return false
        val content = point.copy(x = point.x + contentOffset)
        val bounds = lens.bounds
        if (!bounds.contains(content)) return false
        val radius = lens.corner.coerceIn(0f, minOf(bounds.width, bounds.height) / 2f)
        val x = content.x.coerceIn(bounds.left + radius, bounds.right - radius)
        val y = content.y.coerceIn(bounds.top + radius, bounds.bottom - radius)
        val dx = content.x - x; val dy = content.y - y
        return dx * dx + dy * dy <= radius * radius
    }
}
