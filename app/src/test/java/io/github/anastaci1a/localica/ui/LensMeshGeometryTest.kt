package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class LensMeshGeometryTest {
    @Test fun aUniformOutlinedPillRetainsTheOrdinaryContourMeshExactly() {
        val shape = LensGeometry(Rect(0f, 0f, 120f, 60f), 30f)
        val ordinary = curvedLensMesh(shape, 2.625f)
        val outlined = outlinedLensMesh(listOf(RoundRect(shape.bounds, CornerRadius(30f))), 2.625f, shape::field)
        assertArrayEquals(ordinary.vertices, outlined.vertices, 0f)
        assertArrayEquals(ordinary.indices, outlined.indices)
        assertEquals(ordinary.fields, outlined.fields)
    }

    @Test fun splittingContoursCoverTheirCurvesWithoutReversedOrCollapsedBoundaryTriangles() {
        for (p in listOf(.0001f, .1f, .25f, .4999f, .5f, 1f)) {
            val outlines = splitPillBodies(Size(120f + 10f * ((p - .5f) * 2f).coerceIn(0f, 1f), 60f), p)
            val mesh = outlinedLensMesh(outlines, 2.625f) { LensField(1f, Offset.Zero) }
            val centers = outlines.map { Offset((it.left + it.right) / 2f, (it.top + it.bottom) / 2f) }
            fun point(i: Int) = Offset(mesh.vertices[2 * i], mesh.vertices[2 * i + 1])
            var area = 0.0
            for (i in mesh.indices.indices step 3) {
                val a = point(mesh.indices[i].toInt()); val b = point(mesh.indices[i + 1].toInt()); val c = point(mesh.indices[i + 2].toInt())
                val ab = b - a; val ac = c - a
                val twiceArea = ab.x * ac.y - ab.y * ac.x
                assertTrue("Reversed triangle at p=$p: $a $b $c", twiceArea >= -.00001f)
                if (listOf(a, b, c).none { point -> centers.any { (point - it).getDistance() < .00001f } })
                    assertTrue("Collapsed boundary triangle at p=$p: $a $b $c", twiceArea > .000001f)
                area += twiceArea / 2.0
            }
            val expected = outlines.sumOf { r ->
                val corners = listOf(r.topLeftCornerRadius.x, r.topRightCornerRadius.x, r.bottomLeftCornerRadius.x, r.bottomRightCornerRadius.x)
                r.width * r.height - (1 - PI / 4) * corners.sumOf { (it * it).toDouble() }
            }
            assertEquals("Contour coverage at p=$p", expected, area, expected * .004)
        }
    }

    @Test fun circlesAndCapsulesHaveNoCardinalSliversAwayFromCollapsedCenter() {
        for (density in listOf(1f, 2f, 3f, 4f))
            for ((width, height) in listOf(161f to 161f, 241f to 121f, 121f to 241f)) {
                val shape = LensGeometry(Rect(0f, 0f, width, height), minOf(width, height) / 2f)
                val mesh = curvedLensMesh(shape, density)
                fun point(i: Int) = Offset(mesh.vertices[2 * i], mesh.vertices[2 * i + 1])
                var area = 0.0
                for (i in mesh.indices.indices step 3) {
                    val a = point(mesh.indices[i].toInt())
                    val b = point(mesh.indices[i + 1].toInt())
                    val c = point(mesh.indices[i + 2].toInt())
                    val ab = b - a; val ac = c - a
                    val twiceArea = ab.x * ac.y - ab.y * ac.x
                    assertTrue("Winding at $a $b $c", twiceArea >= 0f)
                    // The final ring intentionally collapses to the center. All
                    // other triangles must have a usable texture transform.
                    if (listOf(a, b, c).none { (it - shape.bounds.center).getDistance() < .00001f })
                        assertTrue("Cardinal sliver at $a $b $c", twiceArea > shape.span * shape.span * 1e-8f)
                    area += twiceArea / 2.0
                }
                val expected = width * height - (4 - PI) * shape.corner * shape.corner
                assertEquals("Full rounded contour coverage", expected, area, expected * .003)
                for (i in mesh.fields.indices) {
                    val p = point(i)
                    assertTrue(p.x >= -.0001f && p.x <= width + .0001f)
                    assertTrue(p.y >= -.0001f && p.y <= height + .0001f)
                    assertTrue(shape.field(p).distance >= -.0001f)
                }
            }
    }
}
