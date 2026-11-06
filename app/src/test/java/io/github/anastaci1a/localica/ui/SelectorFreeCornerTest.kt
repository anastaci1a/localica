package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.*
import org.junit.Test

class SelectorFreeCornerTest {
    @Test fun compressedFreeCornersTightenAcrossAxesEndsRtlAndTrackSizes() {
        for (vertical in listOf(false, true)) for (rtl in listOf(false, true))
            for (end in listOf(-1f, 1f)) for (travel in listOf(192f, 360f, 1800f)) {
                val g = selectorTrackGeometry(travel, 64f, .04f, end, vertical, rtl,
                    reducedMotion = false, overflow = false, heldCrossInset = 4f)
                val frame = g.lens(if (end < 0f) 0f else 2f, 3,
                    SelectorTrackInsets(0f, 4f, 4f), vertical, rtl, GlassShape(-.2f), g.crossExtent / 2f)
                val contour = frame.contour()
                val o = contour.outline
                val negative = frame.wall < 0f
                val near = if (vertical) {
                    if (negative) o.topLeftCornerRadius to o.topRightCornerRadius
                    else o.bottomLeftCornerRadius to o.bottomRightCornerRadius
                } else {
                    if (negative) o.topLeftCornerRadius to o.bottomLeftCornerRadius
                    else o.topRightCornerRadius to o.bottomRightCornerRadius
                }
                val far = if (vertical) {
                    if (negative) o.bottomLeftCornerRadius to o.bottomRightCornerRadius
                    else o.topLeftCornerRadius to o.topRightCornerRadius
                } else {
                    if (negative) o.topRightCornerRadius to o.bottomRightCornerRadius
                    else o.topLeftCornerRadius to o.bottomLeftCornerRadius
                }
                assertEquals(frame.bounds, contour.bounds)
                assertEquals(CornerRadius(frame.corner), near.first)
                assertEquals(near.first, near.second)
                assertEquals(far.first, far.second)
                val freeTravelRadius = if (vertical) far.first.y else far.first.x
                assertTrue("Free corner tightens: vertical=$vertical rtl=$rtl end=$end travel=$travel",
                    freeTravelRadius < frame.corner)
                assertTrue(freeTravelRadius >= 0f)
            }
    }

    @Test fun presentedGapDrivesTheFreeRadiusAndLeavesReturnPhysicsDimensionsIntact() {
        for (vertical in listOf(false, true)) for (end in listOf(-1f, 1f)) {
            val g = selectorTrackGeometry(192f, 64f, .04f, end, vertical, false,
                false, false, heldCrossInset = 4f)
            val insets = SelectorTrackInsets(0f, 4f, 4f)
            val position = if (end < 0f) 0f else 2f
            val held = g.lens(position, 3, insets, vertical, false, GlassShape(-.2f), g.crossExtent / 2f)
            for (gap in listOf(0f, .25f, 1f, 2f, 3.75f, 4f, 5f)) {
                val presented = held.returnFrame(g, vertical).copy(gap = gap)
                val frame = g.lens(position, 3, insets, vertical, false, GlassShape(),
                    g.crossExtent / 2f, presentation = presented)
                assertEquals(presented, frame.returnFrame(g, vertical))
                val free = requireNotNull(frame.freeCorner)
                assertEquals((frame.corner - (4f - gap).coerceAtLeast(0f)).coerceAtLeast(0f),
                    if (vertical) free.y else free.x, .00001f)
                assertEquals(frame.corner, if (vertical) free.x else free.y, 0f)
                assertEquals(56f, if (vertical) frame.bounds.width else frame.bounds.height, .00001f)
            }
        }
    }

    @Test fun mirroredEllipsesOwnMirroredPointsAndTranslateWithoutChangingCurves() {
        for (vertical in listOf(false, true)) {
            val bounds = if (vertical) Rect(0f, 0f, 56f, 80f) else Rect(0f, 0f, 80f, 56f)
            val free = if (vertical) CornerRadius(20f, 12f) else CornerRadius(12f, 20f)
            val a = SelectorTrackContour.ends(bounds, 20f, free, vertical, -1f)
            val b = SelectorTrackContour.ends(bounds, 20f, free, vertical, 1f)
            val offset = Offset(-3.25f, 17.75f)
            val moved = a.translated(offset)
            for (y in 0 until bounds.height.toInt()) for (x in 0 until bounds.width.toInt()) {
                val p = Offset(x + .5f, y + .5f)
                val mirror = if (vertical) Offset(p.x, bounds.height - p.y) else Offset(bounds.width - p.x, p.y)
                assertEquals(a.contains(p), b.contains(mirror))
                assertEquals(a.contains(p), moved.contains(p + offset))
            }
            assertEquals(a.outlineAt(Size(bounds.width * .5f, bounds.height * .5f)).topLeftCornerRadius,
                CornerRadius(a.outline.topLeftCornerRadius.x * .5f, a.outline.topLeftCornerRadius.y * .5f))
        }
    }

    @Test fun spatialContactHandoffPassesThroughRoundWithoutACornerJump() {
        val bounds = Rect(0f, 0f, 80f, 56f)
        var previous: List<CornerRadius>? = null
        for (step in -100..100) {
            val c = SelectorTrackContour.ends(bounds, 20f, CornerRadius(12f, 20f), false, step / 100f)
            val corners = listOf(c.outline.topLeftCornerRadius, c.outline.topRightCornerRadius,
                c.outline.bottomRightCornerRadius, c.outline.bottomLeftCornerRadius)
            previous?.zip(corners)?.forEach { (a, b) ->
                assertTrue(kotlin.math.abs(a.x - b.x) <= .08001f)
                assertEquals(20f, b.y, 0f)
            }
            if (step == 0) assertTrue(c.isUniform)
            previous = corners
        }
    }


    @Test fun restAndReducedMotionKeepTheUniformFastPath() {
        for (vertical in listOf(false, true)) for (rtl in listOf(false, true))
            for (reduced in listOf(false, true)) {
                val g = selectorTrackGeometry(360f, 64f, if (reduced) .04f else 0f, -1f,
                    vertical, rtl, reduced, false, heldCrossInset = 4f)
                val frame = g.lens(0f, 3, SelectorTrackInsets(0f, 4f, 4f), vertical, rtl, GlassShape(), 32f)
                assertTrue(frame.contour().isUniform)
                assertEquals(0f, frame.contour().opticalWeight, 0f)
            }
    }
}
