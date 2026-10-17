package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class GlassSplitGeometryTest {
    @Test fun roundedPixelSizesNormalizeTheSplitCornersWithoutAnEmptyEdge() {
        for (width in listOf(119f, 120f, 121f, 315f)) for (height in listOf(60f, 61f, 158f)) {
            val bounds = Rect(0f, 0f, width, height)
            for (fraction in listOf(.01f, .5f, .9998f)) {
                val field = splitPillDistanceField(bounds.center, bounds, height / 2f * fraction)
                assertTrue(field.distance.isFinite())
                assertEquals(Offset.Zero, field.inward)
            }
        }
    }

    @Test fun analyticSplitBoundaryStaysZeroAndItsNormalsStayFinite() {
        val bounds = Rect(0f, 0f, 120f, 60f)
        for (inner in listOf(.01f, 5f, 15f, 25f, 30f)) {
            for (step in 1..89) {
                val angle = step * kotlin.math.PI.toFloat() / 180f
                val outer = Offset(30f - 30f * kotlin.math.cos(angle), 30f - 30f * kotlin.math.sin(angle))
                val inset = Offset(60f - inner + inner * kotlin.math.cos(angle), inner - inner * kotlin.math.sin(angle))
                for (point in listOf(outer, inset)) {
                    val field = splitPillDistanceField(point, bounds, inner)
                    assertEquals(0f, field.distance, .00002f)
                    assertTrue(field.inward.x.isFinite() && field.inward.y.isFinite())
                    assertTrue(field.inward.getDistance() <= 1.00001f)
                }
            }
            val seam = splitPillDistanceField(Offset(60f, 30f), bounds, inner)
            assertEquals("The shared edge is interior until the neck closes", 30f - inner, seam.distance, .00001f)
            assertEquals(Offset.Zero, seam.inward)
        }
    }

    @Test fun analyticSplitBoundaryBecomesTheExactCircleField() {
        for (x in listOf(5f, 20f, 30f, 45f, 55f)) for (y in listOf(15f, 25f, 35f, 45f)) {
            val point = Offset(x, y)
            val expected = circleLensField(point, Offset(30f, 30f), 30f)
            val actual = splitPillDistanceField(point, Rect(0f, 0f, 120f, 60f), 30f)
            assertEquals(expected.distance, actual.distance, .00002f)
            assertEquals(expected.inward.x, actual.inward.x, .00002f)
            assertEquals(expected.inward.y, actual.inward.y, .00002f)
        }
    }

    @Test fun splitPivotCompensationPreservesEveryBoundaryAtTheCut() {
        val bodies = splitPillBodies(Size(120f, 60f), .5f)
        for (shape in listOf(GlassShape(.08f, -.16f), GlassShape(-.06f, .1f))) {
            bodies.forEachIndexed { index, body ->
                val joined = body.deformed(shape, Offset(60f, 30f))
                val side = if (index == 0) -1f else 1f
                val separate = body.deformed(shape, Offset((body.left + body.right) / 2f, 30f),
                    Offset(side * 30f * (shape.scaleX() - 1f), 0f))
                assertEquals(joined.left, separate.left, .00001f)
                assertEquals(joined.right, separate.right, .00001f)
                assertEquals(joined.top, separate.top, .00001f)
                assertEquals(joined.bottom, separate.bottom, .00001f)
                assertEquals(joined.topLeftCornerRadius, separate.topLeftCornerRadius)
            }
        }
    }

    @Test fun joinedOutlineAndSeparatedCirclesShareOuterEdges() {
        val diameter = 60f
        val joined = splitPillBodies(Size(diameter * 2, diameter), 0f).single()
        assertEquals(0f, joined.left, 0f)
        assertEquals(120f, joined.right, 0f)
        val separated = splitPillBodies(Size(130f, diameter), 1f)
        assertEquals(2, separated.size)
        separated.forEach {
            assertEquals(diameter, it.width, .001f)
            assertEquals(diameter, it.height, .001f)
            assertEquals(30f, it.topLeftCornerRadius.x, .001f)
            assertEquals(it.topLeftCornerRadius, it.bottomRightCornerRadius)
        }
        assertEquals(10f, separated[1].left - separated[0].right, .001f)
    }

    @Test fun neckClosesCompletelyBeforeAVisibleGapOpens() {
        val touching = splitPillBodies(Size(120f, 60f), .5f)
        assertEquals(touching[0].right, touching[1].left, 0f)
        assertEquals("No finite straight bridge may be removed at separation", 60f,
            touching[0].topRightCornerRadius.y + touching[0].bottomRightCornerRadius.y, 0f)
        val separated = splitPillBodies(Size(120.002f, 60f), .5001f)
        assertTrue(separated[1].left > separated[0].right)
        assertEquals(touching[0].topRightCornerRadius, separated[0].topRightCornerRadius)
    }

    @Test fun endpointsUseTheOrdinaryAnalyticPillAndCircleFields() {
        val joined = splitPillGeometry(Size(120f, 60f), 0f)
        assertEquals(0f, joined.fieldWeight, 0f)
        assertNull(joined.circles)
        val split = splitPillGeometry(Size(130f, 60f), 1f)
        assertEquals(0f, split.fieldWeight, 0f)
        assertEquals(Offset(30f, 30f) to Offset(100f, 30f), split.circles)
        val circle = LensGeometry(Rect(0f, 0f, 60f, 60f), 30f)
        for (point in listOf(Offset(30f, 30f), Offset(5f, 30f), Offset(40f, 40f), Offset(30f, 58f))) {
            val expected = circle.field(point)
            val actual = circleLensField(point, Offset(30f, 30f), 30f)
            assertEquals(expected.distance, actual.distance, .001f)
            assertEquals(expected.inward.x, actual.inward.x, .001f)
            assertEquals(expected.inward.y, actual.inward.y, .001f)
        }
    }

    @Test fun theIntermediateBoundaryHidesTheAnalyticEndpointHandoff() {
        assertEquals(1f, splitPillGeometry(Size(120f, 60f), .25f).fieldWeight, 0f)
        for (p in listOf(.24999f, .25001f)) {
            assertTrue(splitPillGeometry(Size(120f, 60f), p).fieldWeight > .9999f)
        }
        assertEquals(0f, splitPillGeometry(Size(120f, 60f), .5f).fieldWeight, 0f)
    }

    @Test fun theConnectedOpticalNeckHasNoNormalSeamAndMatchesItsHeightGradient() {
        val centers = Offset(30f, 30f) to Offset(90f, 30f)
        fun field(point: Offset) = pairedCircleLensField(point, centers, 30f, .6f)
        assertEquals(0f, field(Offset(60f, 30f)).inward.x, .00001f)
        for (point in listOf(Offset(52f, 24f), Offset(60f, 30f), Offset(68f, 36f))) {
            val dx = (field(point + Offset(.01f, 0f)).distance - field(point - Offset(.01f, 0f)).distance) / .02f
            val dy = (field(point + Offset(0f, .01f)).distance - field(point - Offset(0f, .01f)).distance) / .02f
            assertEquals(dx, field(point).inward.x, .002f)
            assertEquals(dy, field(point).inward.y, .002f)
        }
    }

    @Test fun reversingPreparationRetracesTheSameGeometryWithoutOverlap() {
        val forward = (1..100).map { step ->
            val p = step / 100f
            splitPillBodies(Size(120f + 10f * ((p - .5f) * 2f).coerceIn(0f, 1f), 60f), p)
        }
        forward.forEach { bodies ->
            assertTrue(bodies[0].right <= bodies[1].left)
            assertEquals(bodies[0].width, bodies[1].width, .001f)
            assertEquals(bodies[0].topRightCornerRadius, bodies[1].topLeftCornerRadius)
        }
        (100 downTo 1).forEach { step ->
            val p = step / 100f
            assertEquals(forward[step - 1], splitPillBodies(
                Size(120f + 10f * ((p - .5f) * 2f).coerceIn(0f, 1f), 60f), p))
        }
    }
}
