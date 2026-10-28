package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class SelectionContourTest {
    @Test fun axisIntervalsMatchIndependentRoundedBodyMembershipAndPreserveThinHoles() {
        val regions = listOf(Rect(0f, 0f, 80f, 40f), Rect(0f, 48f, 80f, 88f), Rect(88f, 48f, 168f, 88f)).map(::SelectionRegion)
        val contour = selectionContour(regions, 8f)
        val random = Random(466)
        repeat(3000) {
            val x = random.nextDouble(-20.0, 190.0); val y = random.nextDouble(-20.0, 110.0)
            val inside = contour.bodies.any { body ->
                val r = body.bounds
                val qx = abs(x - (r.left.toDouble() + r.right) / 2) - r.width / 2 + body.radius
                val qy = abs(y - (r.top.toDouble() + r.bottom) / 2) - r.height / 2 + body.radius
                sqrt(max(qx, 0.0) * max(qx, 0.0) + max(qy, 0.0) * max(qy, 0.0)) + min(max(qx, qy), 0.0) <= body.radius
            }
            assertEquals(inside, contour.intervals(true, y).any { x >= it.start && x <= it.end })
            assertEquals(inside, contour.intervals(false, x).any { y >= it.start && y <= it.end })
        }
        val split = SelectionContour(listOf(SelectionContourBody(Rect(0f, 0f, .49999f, 1f), 0f),
            SelectionContourBody(Rect(.50001f, 0f, 1f, 1f), 0f)))
        assertEquals(2, split.intervals(true, .5).size)
        assertEquals(.4995, requireNotNull(split.firstExit(true, .5, .49, .51)), .000002)
        assertEquals(.4995, requireNotNull(split.firstExit(true, .5, .51, .49)), .000002)
        assertNull(split.firstExit(true, .5, .1, .4))
        assertNull(split.firstExit(true, .5, .1, .1))
    }

    @Test fun containingReferencePreservesSimpleLimitAndIncludesBridgeConstraints() {
        val one = SelectionContour(listOf(SelectionContourBody(Rect(0f, 0f, 100f, 80f), 12f)))
        assertEquals(12f, requireNotNull(one.referenceGeometry(12f)).corner, 0f)
        val narrowInside = SelectionContour(one.bodies + SelectionContourBody(Rect(45f, 30f, 49f, 34f), 2f))
        assertEquals(12f, requireNotNull(narrowInside.referenceGeometry(12f)).corner, 0f)
        val narrowOutside = SelectionContour(listOf(SelectionContourBody(Rect(0f, 0f, 4f, 40f), 2f),
            SelectionContourBody(Rect(4f, 0f, 100f, 40f), 12f)))
        assertEquals(2f, requireNotNull(narrowOutside.referenceGeometry(12f)).corner, 0f)
        val bridged = selectionContour(listOf(SelectionRegion(Rect(0f, 0f, 10f, 10f)),
            SelectionRegion(Rect(14f, 0f, 24f, 10f))), 4f)
        assertEquals(4f, requireNotNull(bridged.referenceGeometry(6f)).corner, 0f)
        for (epsilon in listOf(1f, .01f, .0001f, 0f)) {
            val aligned = selectionContour(listOf(SelectionRegion(Rect(0f, 0f, 80f, 40f)),
                SelectionRegion(Rect(epsilon, 44f, 80f + epsilon, 84f))), 4f)
            assertEquals(6f, requireNotNull(aligned.referenceGeometry(6f)).corner, .00001f)
        }
        val moved = SelectionContour(bridged.bodies.map { body ->
            val b = body.bounds
            SelectionContourBody(Rect(b.left * 3f + 137f, b.top * 3f - 51f,
                b.right * 3f + 137f, b.bottom * 3f - 51f), body.radius * 3f)
        })
        assertEquals(12f, requireNotNull(moved.referenceGeometry(18f)).corner, 0f)
        assertNull(SelectionContour(emptyList()).referenceGeometry(12f))
    }
}
