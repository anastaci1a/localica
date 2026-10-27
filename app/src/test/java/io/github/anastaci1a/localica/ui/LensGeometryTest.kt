package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class LensGeometryTest {
    @Test fun maximumOpticalDepthCannotSampleBeyondTheSelectionAtlasMargin() {
        for (width in listOf(64f, 320f, 800f)) for (height in listOf(64f, 120f, 900f)) {
            val span = minOf(width, height) / 2f
            for (corner in listOf(0f, 16f, span)) for (depth in listOf(.05f, 1.35f, 4f)) {
                val shape = LensGeometry(Rect(0f, 0f, width, height), corner)
                for (x in 0..40) for (y in 0..40) {
                    val ray = lensRay(shape.field(Offset(width * x / 40f, height * y / 40f)), span, depth)
                    assertTrue("Cropping must retain every ray at maximum supported depth", ray.offset.getDistance() <= span * 2.1f)
                }
            }
        }
    }

    @Test fun boxCrownHasNoDirectionalWedgesThroughItsCenter() {
        val shape = LensGeometry(Rect(0f, 0f, 400f, 400f), 28f)
        fun ray(x: Float, y: Float) = lensRay(shape.field(shape.bounds.center + Offset(x, y)), shape.span, 1.35f)
        for (distance in listOf(2f, 5f, 10f)) {
            val axial = ray(distance, 0f).offset.x
            val diagonal = ray(distance, distance).offset.x
            assertEquals("Smooth central curvature cannot depend on an angular sector", axial, diagonal, kotlin.math.abs(axial) * .05f)
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

    @Test fun compressionFlattensTheCenterAndConcentratesRemainingPowerAtTheRim() {
        val circle = LensGeometry(Rect(0f, 0f, 100f, 100f), 50f)
        fun bend(x: Float, pressed: Boolean) = lensRay(circle.field(Offset(x, 50f)), circle.span,
            if (pressed) 1.12f else 1.35f, if (pressed) 1f else 0f).offset.getDistance()
        assertTrue(bend(60f, true) < bend(60f, false) * .5f)
        assertTrue(bend(98f, true) < bend(98f, false))
        assertTrue(bend(60f, true) / bend(98f, true) < bend(60f, false) / bend(98f, false) * .6f)
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

    @Test fun panelNormalDoesNotJumpAcrossItsInteriorDiagonals() {
        val shape = LensGeometry(Rect(0f, 0f, 400f, 400f), 28f)
        for (coordinate in 40..180 step 5) {
            val left = lensRay(shape.field(Offset(coordinate - .01f, coordinate + .01f)), shape.span, 1.35f)
            val right = lensRay(shape.field(Offset(coordinate + .01f, coordinate - .01f)), shape.span, 1.35f)
            assertTrue("No refraction seam at $coordinate", (left.offset - right.offset).getDistance() < .1f)
            assertEquals(left.height, right.height, .001f)
        }
    }

    @Test fun roundedPanelGradientMatchesItsContinuousHeightCoordinate() {
        val shape = LensGeometry(Rect(0f, 0f, 320f, 400f), 24f)
        for (x in 30..290 step 13) for (y in 30..370 step 17) {
            val p = Offset(x.toFloat(), y.toFloat())
            val field = shape.field(p)
            val dx = (shape.field(p + Offset(.05f, 0f)).distance - shape.field(p - Offset(.05f, 0f)).distance) / .1f
            val dy = (shape.field(p + Offset(0f, .05f)).distance - shape.field(p - Offset(0f, .05f)).distance) / .1f
            assertEquals(dx, field.inward.x, .004f)
            assertEquals(dy, field.inward.y, .004f)
        }
    }

    @Test fun contourMeshSamplesTheSameSurfaceWithoutFoldedTriangles() {
        for (shape in listOf(LensGeometry(Rect(0f, 0f, 260f, 64f), 32f),
            LensGeometry(Rect(13f, 27f, 333f, 427f), 24f),
            LensGeometry(Rect(0f, 0f, 96f, 96f), 48f))) {
            val mesh = curvedLensMesh(shape, 1f)
            assertEquals(mesh.fields.size * 2, mesh.vertices.size)
            for (i in mesh.fields.indices) {
                val point = Offset(mesh.vertices[2 * i], mesh.vertices[2 * i + 1])
                assertEquals(shape.field(point).distance, mesh.fields[i].distance, .001f)
            }
            for (i in mesh.indices.indices step 3) {
                fun point(index: Int): Offset {
                    val vertex = mesh.indices[index].toInt()
                    assertTrue(vertex in mesh.fields.indices)
                    return Offset(mesh.vertices[2 * vertex], mesh.vertices[2 * vertex + 1])
                }
                val a = point(i); val b = point(i + 1) - a; val c = point(i + 2) - a
                assertTrue("Contour triangles must not fold over", b.x * c.y - b.y * c.x >= -.001f)
            }
        }
    }

    @Test fun sampledGradientsProduceTheSameNormalizedSurfaceAsTheShader() {
        val flat = lensRay(LensField(0f, Offset.Zero), 50f, 1.35f)
        assertEquals(1f, flat.normalZ, .0001f)
        assertEquals(Offset.Zero, flat.offset)
        val sampled = lensRay(LensField(15f, Offset(.3f, .4f)), 50f, 1.35f)
        assertEquals(1f, sampled.normalXY.getDistanceSquared() + sampled.normalZ * sampled.normalZ, .0001f)
        assertTrue(sampled.normalZ > 0f && sampled.normalZ < 1f)
    }

    @Test fun expandedPanelHasCurvatureBeyondItsOutlineCorners() {
        val panel = LensGeometry(Rect(0f, 0f, 320f, 400f), 24f)
        val normal = lensRay(panel.field(Offset(80f, 200f)), panel.span, 1.35f)
        assertTrue("The face must remain curved well inside the 24px outline corner", normal.normalZ < .995f)
        assertTrue(abs(normal.offset.x) > 4f)
        assertEquals(0f, lensRay(panel.field(panel.bounds.center), panel.span, 1.35f).offset.getDistance(), .0001f)
    }

    @Test fun refractionIsSymmetricNonlinearAndRespondsToDepth() {
        val shape = LensGeometry(Rect(0f, 0f, 100f, 100f), 50f)
        fun ray(x: Float, depth: Float = 1.35f) = lensRay(shape.field(Offset(x, 50f)), shape.span, depth)
        assertEquals(-ray(20f).offset.x, ray(80f).offset.x, .0001f)
        assertTrue("A lens must not be a uniform scale", abs(ray(90f).offset.x / 40f - ray(70f).offset.x / 20f) > .03f)
        assertTrue("Held flattening changes both bend and normal", abs(ray(80f, .8f).offset.x) < abs(ray(80f, 1.35f).offset.x))
        assertTrue(ray(80f, .8f).normalZ > ray(80f, 1.35f).normalZ)
    }

    @Test fun centralContentRetainsScaleWhileThePeripheralBandCompresses() {
        val shape = LensGeometry(Rect(0f, 0f, 100f, 100f), 50f)
        fun ray(x: Float) = lensRay(shape.field(Offset(x, 50f)), shape.span, 1.35f)
        assertTrue("Central magnification must stay below 12%", abs(ray(55f).offset.x) < .12f * 5f)
        val outsideBand = (99.9f + ray(99.9f).offset.x) - (99f + ray(99f).offset.x)
        assertTrue("The shoulder must compress multiple source pixels into its narrow rim", abs(outsideBand) > 1.8f)
        for (x in 55..95 step 5) {
            val p = x.toFloat()
            val derivative = (ray(p + .01f).height - ray(p - .01f).height) / .02f
            val normal = ray(p)
            assertEquals("Lighting and refraction follow the same curved crown", -derivative, normal.normalXY.x / normal.normalZ, .01f)
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
        assertEquals(expected.span, geometry.field(expected.bounds.center).distance, 0f)
        assertEquals(Offset.Zero, geometry.field(expected.bounds.center).inward)
        assertEquals(-3f, geometry.field(Offset(expected.bounds.left - 3f, expected.bounds.center.y)).distance, .0001f)
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
