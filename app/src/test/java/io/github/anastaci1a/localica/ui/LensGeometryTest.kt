package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class LensGeometryTest {
    // Each producer's own padding: LiquidGlassSurface and the slider scene 32 dp, the switch scene 24 dp.
    @Test fun everyLookupStaysInsideTheRecordingMargin() {
        for (pad in listOf(32f, 24f)) for (unit in listOf(1f, 2.48125f, 3f)) for (width in listOf(24f, 48f, 160f)) for (height in listOf(24f, 48f, 300f)) {
            val span = minOf(width, height) / 2f
            for (corner in listOf(0f, 16f, span)) {
                val shape = LensGeometry(Rect(0f, 0f, width * unit, height * unit), corner * unit)
                val reach = shape.bounds.inflate(pad * unit)
                for (x in 0..40) for (y in 0..40) {
                    val p = Offset(shape.bounds.width * x / 40f, shape.bounds.height * y / 40f)
                    val lookup = GlassRefractionReference.at(shape.field(p), unit)
                    assertTrue("Cropping must retain every lookup", reach.contains(p + lookup.inner) && reach.contains(p + lookup.outer))
                }
            }
        }
    }

    @Test fun circularCrownRetainsItsRadialProfile() {
        val shape = LensGeometry(Rect(0f, 0f, 100f, 100f), 50f)
        for (radius in 1..49) for (angle in 0..360 step 15) {
            val radians = angle * kotlin.math.PI.toFloat() / 180f
            val p = Offset(kotlin.math.cos(radians), kotlin.math.sin(radians)) * radius.toFloat()
            val field = shape.field(shape.bounds.center + p)
            assertEquals(50f - radius, field.distance, .0002f)
            assertEquals(-p.x / radius, field.inward.x, .0003f)
            assertEquals(-p.y / radius, field.inward.y, .0003f)
        }
    }

    @Test fun boundaryNormalsSurviveMeshRounding() {
        val shape = LensGeometry(Rect(0f, 0f, 320f, 400f), 24f)
        for (y in listOf(40f, 120f, 200f)) {
            val inside = shape.field(Offset(319.999f, y))
            val boundary = shape.field(Offset(320f, y))
            val outside = shape.field(Offset(320.00003f, y))
            assertEquals(inside.inward.x, boundary.inward.x, .003f)
            assertEquals(boundary.inward.x, outside.inward.x, .003f)
        }
    }

    @Test fun scrollingKeepsGeometryStableAtFractionalCoordinates() {
        val expected = listOf(SelectionRegion(Rect(0f, 0f, 300f, 64f)), SelectionRegion(Rect(0f, 72f, 300f, 136f)))
        for (i in 0..240) {
            val origin = Offset(13.123f, -i / 3f + .124f)
            val translated = expected.map { it.copy(bounds = it.bounds.translate(origin)) }
            assertEquals(expected, localLensRegions(translated, origin))
        }
    }

    @Test fun analyticSelectionNeverFillsHolesOrDiagonalGaps() {
        val first = SelectionRegion(Rect(0f, 0f, 100f, 60f))
        assertNotNull(simpleLensGeometry(listOf(first, SelectionRegion(Rect(0f, 68f, 100f, 128f))), 16f))
        assertNull(simpleLensGeometry(listOf(first, SelectionRegion(Rect(0f, 90f, 100f, 150f))), 16f))
        assertNull(simpleLensGeometry(listOf(first, SelectionRegion(Rect(108f, 68f, 208f, 128f))), 16f))
    }

    private fun assertOuterGeometry(expected: LensGeometry, actual: LensGeometry?) {
        val geometry = requireNotNull(actual)
        assertEquals(expected.bounds, geometry.bounds)
        assertEquals(expected.corner, geometry.corner, 0f)
        assertEquals(expected.span, geometry.field(expected.bounds.center).distance, .0001f)
        assertEquals(1f, geometry.field(expected.bounds.center).inward.getDistance(), .0001f)
        // Continuous corners may bend a short end slightly outward of its circular outline.
        assertEquals(-3f, geometry.field(Offset(expected.bounds.left - 3f, expected.bounds.center.y)).distance, .1f)
        // The oracle is the independently specified outer roundrect, not a region
        // hull or any field sampled from simpleLensGeometry's previous result.
        for (x in 0..12) for (y in 0..8) {
            val point = Offset(expected.bounds.left + expected.bounds.width * x / 12f,
                expected.bounds.top + expected.bounds.height * y / 8f)
            val wanted = expected.field(point)
            val found = geometry.field(point)
            assertEquals(wanted.distance, found.distance, .0001f)
            assertEquals(wanted.inward.x, found.inward.x, .0001f)
            assertEquals(wanted.inward.y, found.inward.y, .0001f)
        }
    }

    @Test fun horizontalCompleteChainUsesTheIndependentOuterRoundrectForEveryInputOrder() {
        val parts = listOf(SelectionRegion(Rect(13f, 27f, 113f, 91f)),
            SelectionRegion(Rect(121f, 27f, 241f, 91f)), SelectionRegion(Rect(257f, 27f, 337f, 91f)))
        val expected = LensGeometry(Rect(13f, 27f, 337f, 91f), 24f)
        for (first in parts.indices) for (second in parts.indices.filter { it != first }) {
            val last = parts.indices.single { it != first && it != second }
            val input = listOf(parts[first], parts[second], parts[last])
            val untouched = input.toList()
            assertOuterGeometry(expected, simpleLensGeometry(input, 16f))
            assertEquals(untouched, input)
        }
    }

    @Test fun horizontalGapBoundaryAndTouchingBodiesRemainExactAtFractionalOrigins() {
        val a = SelectionRegion(Rect(13.25f, -8.5f, 113.25f, 55.5f))
        for (separation in listOf(0f, 8f, 16f)) {
            val b = SelectionRegion(Rect(113.25f + separation, -8.5f, 213.25f + separation, 55.5f))
            assertOuterGeometry(LensGeometry(Rect(13.25f, -8.5f, 213.25f + separation, 55.5f), 24f),
                simpleLensGeometry(listOf(b, a), 16f))
        }
        val tooFar = java.lang.Math.nextUp(129.25f)
        assertNull(simpleLensGeometry(listOf(a, SelectionRegion(Rect(tooFar, -8.5f, tooFar + 100f, 55.5f))), 16f))
        assertOuterGeometry(LensGeometry(Rect(0f, 0f, 200f, 64f), 0f), simpleLensGeometry(listOf(
            SelectionRegion(Rect(0f, 0f, 100f, 64f)), SelectionRegion(Rect(100f, 0f, 200f, 64f))), 0f))
    }

    @Test fun horizontalRecognitionRejectsNonrectangularHullsOverlapsAndUnequalExtents() {
        val a = SelectionRegion(Rect(0f, 0f, 100f, 64f))
        val invalid = listOf(
            listOf(a, SelectionRegion(Rect(117f, 0f, 217f, 64f))),
            listOf(a, SelectionRegion(Rect(99f, 0f, 199f, 64f))),
            listOf(a, a),
            listOf(a, SelectionRegion(Rect(108f, 1f, 208f, 64f))),
            listOf(a, SelectionRegion(Rect(108f, 0f, 208f, 63f))),
            listOf(a, SelectionRegion(Rect(108f, 72f, 208f, 136f))),
            listOf(a, SelectionRegion(Rect(108f, 0f, 208f, 64f)), SelectionRegion(Rect(108f, 72f, 208f, 136f)))
        )
        for (input in invalid) {
            assertNull(simpleLensGeometry(input, 16f))
            assertNull(simpleLensGeometry(input.reversed(), 16f))
        }
    }

    @Test fun horizontalBridgesCannotOverwriteNarrowOuterCorners() {
        // A bridge's24px shoulder must not run into an outside cap. Equal height
        // alone is insufficient, even when the per-item radius is height-clamped.
        assertNull(simpleLensGeometry(listOf(SelectionRegion(Rect(0f, 0f, 47f, 64f)),
            SelectionRegion(Rect(63f, 0f, 163f, 64f))), 16f))
        assertNull(simpleLensGeometry(listOf(SelectionRegion(Rect(0f, 0f, 30f, 20f)),
            SelectionRegion(Rect(46f, 0f, 126f, 20f))), 16f))
        assertOuterGeometry(LensGeometry(Rect(0f, 0f, 146f, 64f), 24f), simpleLensGeometry(listOf(
            SelectionRegion(Rect(0f, 0f, 48f, 64f)), SelectionRegion(Rect(64f, 0f, 146f, 64f))), 16f))
        assertOuterGeometry(LensGeometry(Rect(0f, 0f, 156f, 20f), 10f), simpleLensGeometry(listOf(
            SelectionRegion(Rect(0f, 0f, 60f, 20f)), SelectionRegion(Rect(76f, 0f, 156f, 20f))), 16f))
    }

    @Test fun verticalAndSingleItemRoutingRetainTheirExistingBoundsRadiusAndFields() {
        val vertical = listOf(SelectionRegion(Rect(11f, 20f, 311f, 84f)),
            SelectionRegion(Rect(11f, 92f, 311f, 172f)), SelectionRegion(Rect(11f, 188f, 311f, 288f)))
        assertOuterGeometry(LensGeometry(Rect(11f, 20f, 311f, 288f), 24f), simpleLensGeometry(vertical, 16f))
        assertOuterGeometry(LensGeometry(Rect(11f, 20f, 311f, 288f), 24f), simpleLensGeometry(vertical.reversed(), 16f))
        assertOuterGeometry(LensGeometry(Rect(5f, 7f, 15f, 71f), 5f),
            simpleLensGeometry(listOf(SelectionRegion(Rect(5f, 7f, 15f, 71f))), 16f))
    }

    @Test fun invalidDimensionsAndGapNeverEnterEitherSimpleRoute() {
        val valid = SelectionRegion(Rect(0f, 0f, 100f, 64f))
        assertNull(simpleLensGeometry(emptyList(), 16f))
        for (gap in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY))
            assertNull(simpleLensGeometry(listOf(valid), gap))
        for (bounds in listOf(Rect(0f, 0f, 0f, 64f), Rect(1f, 0f, 0f, 64f),
            Rect(0f, 64f, 100f, 64f), Rect(Float.NaN, 0f, 100f, 64f),
            Rect(0f, 0f, Float.POSITIVE_INFINITY, 64f), Rect(-Float.MAX_VALUE, 0f, Float.MAX_VALUE, 64f)))
            assertNull(simpleLensGeometry(listOf(valid, SelectionRegion(bounds)), 16f))
    }
}
