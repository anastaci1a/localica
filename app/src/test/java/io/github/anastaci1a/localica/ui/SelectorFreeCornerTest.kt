package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.CornerRadius
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
}
