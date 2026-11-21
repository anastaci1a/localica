package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.boundingRect
import io.github.anastaci1a.localica.ui.engine.deformed
import org.junit.Assert.*
import org.junit.Test

class GlassSplitGeometryTest {
    private fun lens(size: Size, p: Float) = GlassSilhouetteFrame(splitPillOptics(splitPillBodies(size, p), p)).geometry
    private val seamDepth = 420f / (1 shl 24) // H(H(H(1e-4) * 0.5) * 0.5), from Compose 58.

    @Test fun roundedPixelSizesNormalizeTheSplitCornersWithoutAnEmptyEdge() {
        for (width in listOf(119f, 120f, 121f, 315f)) for (height in listOf(60f, 61f, 158f)) {
            val bounds = Rect(0f, 0f, width, height)
            for (fraction in listOf(.01f, .5f, .9998f)) {
                val field = lens(bounds.size, fraction / 2f).field(bounds.center)
                assertTrue(field.distance.isFinite())
                assertTrue(field.distance > 0f)
                assertEquals(0f, field.inward.getDistance(), .00001f)
            }
        }
    }

    @Test fun analyticSplitBoundaryStaysZeroAndItsNormalsStayFinite() {
        val bounds = Rect(0f, 0f, 120f, 60f)
        for (inner in listOf(.01f, 5f, 15f, 25f, 30f)) {
            val geometry = lens(bounds.size, inner / 60f)
            for (step in 1..89) {
                val angle = step * kotlin.math.PI.toFloat() / 180f
                val outer = Offset(30f - 30f * kotlin.math.cos(angle), 30f - 30f * kotlin.math.sin(angle))
                val cos = kotlin.math.cos(angle)
                val sin = kotlin.math.sin(angle)
                val inset = Offset(60f - inner + inner * cos, inner - inner * sin)
                val toOtherCenter = Offset(60f + inner, inner) - inset
                val otherRadius = toOtherCenter.getDistance()
                val insetDepth = composeLensReference(LensField(0f, Offset(-cos, sin)),
                    LensField(inner - otherRadius, toOtherCenter / otherRadius)).distance
                for ((point, expected) in listOf(outer to 0f, inset to insetDepth)) {
                    val field = geometry.field(point)
                    assertEquals(expected, field.distance, .00002f)
                    assertTrue(field.inward.x.isFinite() && field.inward.y.isFinite())
                    assertTrue(field.inward.getDistance() <= 1.00001f)
                }
            }
            val seam = geometry.field(Offset(60f, 30f))
            assertEquals("The shared edge carries the Compose 58 correction", seamDepth, seam.distance, .00001f)
            assertEquals(0f, seam.inward.getDistance(), .00001f)
        }
    }

    @Test fun analyticSplitBoundaryBecomesTheExactCircleField() {
        for (x in listOf(5f, 20f, 30f, 45f, 55f)) for (y in listOf(15f, 25f, 35f, 45f)) {
            val point = Offset(x, y)
            val expected = LensGeometry(Rect(0f, 0f, 60f, 60f), 30f).field(point)
            val actual = lens(Size(120f, 60f), .5f).field(point)
            assertEquals(expected.distance.lensHalf(), actual.distance, .00002f)
            if (point == Offset(30f, 30f)) continue
            assertEquals(expected.inward.x.lensHalf(), actual.inward.x, .00002f)
            assertEquals(expected.inward.y.lensHalf(), actual.inward.y, .00002f)
        }
    }

    @Test fun splitPivotCompensationPreservesEveryBoundaryAtTheCut() {
        val bodies = splitPillBodies(Size(120f, 60f), .5f)
        for (shape in listOf(GlassShape(.08f, -.16f), GlassShape(-.06f, .1f))) {
            bodies.forEachIndexed { index, body ->
                val scale = GlassBodyTransform(Offset(shape.scaleX(), shape.scaleY()))
                val joined = body.deformed(scale, Offset(60f, 30f))
                val side = if (index == 0) -1f else 1f
                val separate = body.deformed(scale.copy(drift = Offset(side * 30f * (shape.scaleX() - 1f), 0f)), Offset((body.left + body.right) / 2f, 30f))
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
        val (joined, neck) = splitPillBodies(Size(diameter * 2, diameter), 0f)
        assertEquals("B1: the second body rests as a zero-width neck", Rect(60f, 0f, 60f, 60f), Rect(neck.left, neck.top, neck.right, neck.bottom))
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
        val joined = lens(Size(120f, 60f), 0f)
        val split = lens(Size(130f, 60f), 1f)
        assertEquals(1, joined.bodies.size)
        assertEquals(listOf(Offset(30f, 30f), Offset(100f, 30f)), split.bodies.map { it.boundingRect.center })
        val circle = LensGeometry(Rect(0f, 0f, 60f, 60f), 30f)
        val pill = LensGeometry(Rect(0f, 0f, 120f, 60f), 30f)
        for (point in listOf(Offset(30f, 30f), Offset(5f, 30f), Offset(40f, 40f), Offset(30f, 58f))) {
            assertEquals(pill.field(point), joined.field(point))
            val expected = circle.field(point)
            val actual = split.field(point)
            assertEquals(expected.distance.lensHalf(), actual.distance, .001f)
            if (point == circle.bounds.center) continue
            assertEquals(expected.inward.x.lensHalf(), actual.inward.x, .001f)
            assertEquals(expected.inward.y.lensHalf(), actual.inward.y, .001f)
        }
    }

    @Test fun theComposedFieldKeepsTheSeamThroughFormerBlendTransitions() {
        val size = Size(120f, 60f)
        val seam = Offset(60f, 30f)
        for (p in listOf(.24999f, .25f, .25001f, .49999f, .5f)) {
            assertEquals(seamDepth, lens(size, p).field(seam).distance, .00001f)
        }
    }

    @Test fun theConnectedOpticalNeckCarriesTheOpposedNormalMix() {
        val geometry = lens(Size(120f, 60f), .3f)
        fun field(point: Offset) = geometry.field(point)
        val medial = field(Offset(60f, 30f))
        assertEquals(seamDepth, medial.distance, .00001f)
        assertEquals(0f, medial.inward.x, .00001f)
        assertEquals(0f, medial.inward.y, .00001f)
        for (side in listOf(-1f, 1f)) {
            val flank = field(Offset(60f, 30f + side * .25f))
            assertEquals(seamDepth, flank.distance, .00001f)
            assertEquals(0f, flank.inward.x, .00001f)
            assertEquals(0f, flank.inward.y, .00001f)
        }
        assertEquals(0f, field(Offset(60f, 24f)).inward.x, .00001f)
        for ((point, expected) in listOf(Offset(52f, 24f) to Offset(-1f, 0f),
            Offset(60f, 24f) to Offset.Zero, Offset(68f, 36f) to Offset(1f, 0f))) {
            assertEquals(expected.x, field(point).inward.x, .002f)
            assertEquals(expected.y, field(point).inward.y, .002f)
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
