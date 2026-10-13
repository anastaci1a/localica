package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class LensGeometryTest {
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
        assertTrue("The face must remain curved well inside the 24px outline corner", normal.normalZ < .95f)
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
