package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class LensGeometryTest {
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
}
