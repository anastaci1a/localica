package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class LensMeshGeometryTest {
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
