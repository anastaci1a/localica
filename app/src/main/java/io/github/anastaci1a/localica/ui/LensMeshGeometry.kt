package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import kotlin.math.*

internal data class LensMeshGeometry(val vertices: FloatArray, val fields: List<LensField>, val indices: ShortArray)

/** Follow the rounded contour and concentrate samples where the crown turns most
 * quickly. A uniform Cartesian grid undersamples that narrow curved shoulder. */
internal fun curvedLensMesh(shape: LensGeometry, density: Float): LensMeshGeometry {
    val bounds = shape.bounds
    val radius = shape.corner.coerceIn(0f, shape.span)
    // A circle/capsule has no straight edge on one or both axes. Keeping a
    // zero-length edge creates cardinal sliver triangles after trig rounding;
    // software Canvas can sample those with an effectively singular transform.
    val horizontal = ceil((bounds.width - 2 * radius) / (8 * density)).toInt().coerceIn(0, 64)
    val vertical = ceil((bounds.height - 2 * radius) / (8 * density)).toInt().coerceIn(0, 64)
    val arc = ceil(PI.toFloat() * radius / (4 * density)).toInt().coerceIn(12, 32)
    val perimeter = 2 * horizontal + 2 * vertical + 4 * arc
    val rings = 32
    val vertices = FloatArray((rings + 1) * perimeter * 2)
    val fields = ArrayList<LensField>((rings + 1) * perimeter)
    var cursor = 0
    for (ring in 0..rings) {
        val fraction = ring.toFloat() / rings
        val scale = 1f - fraction * fraction
        val r = radius * scale
        val left = bounds.center.x - bounds.width * scale / 2f; val right = bounds.center.x + bounds.width * scale / 2f
        val top = bounds.center.y - bounds.height * scale / 2f; val bottom = bounds.center.y + bounds.height * scale / 2f
        fun point(x: Float, y: Float) {
            vertices[cursor++] = x; vertices[cursor++] = y
            fields += shape.field(Offset(x, y))
        }
        fun line(x: Float, y: Float, dx: Float, dy: Float, count: Int) {
            for (i in 0 until count) point(x + dx * i / count, y + dy * i / count)
        }
        fun corner(x: Float, y: Float, quadrant: Int) {
            for (i in 0 until arc) {
                val angle = (quadrant + i.toFloat() / arc) * PI.toFloat() / 2
                val nx = cos(angle); val ny = sin(angle)
                point(x + r * nx, y + r * ny)
            }
        }
        line(left + r, top, right - left - 2 * r, 0f, horizontal)
        corner(right - r, top + r, -1)
        line(right, top + r, 0f, bottom - top - 2 * r, vertical)
        corner(right - r, bottom - r, 0)
        line(right - r, bottom, -(right - left - 2 * r), 0f, horizontal)
        corner(left + r, bottom - r, 1)
        line(left, bottom - r, 0f, -(bottom - top - 2 * r), vertical)
        corner(left + r, top + r, 2)
    }
    val indices = ShortArray(rings * perimeter * 6)
    var at = 0
    for (ring in 0 until rings) for (edge in 0 until perimeter) {
        val next = (edge + 1) % perimeter
        val a = ring * perimeter + edge; val b = ring * perimeter + next
        val c = a + perimeter; val d = b + perimeter
        indices[at++] = a.toShort(); indices[at++] = b.toShort(); indices[at++] = c.toShort()
        indices[at++] = b.toShort(); indices[at++] = d.toShort(); indices[at++] = c.toShort()
    }
    return LensMeshGeometry(vertices, fields, indices)
}

/** Arbitrary joined selections retain their cached distance field and holes. */
internal fun sampledLensMesh(shape: LensGeometry, density: Float, field: (Offset) -> LensField): LensMeshGeometry {
    val r = shape.bounds
    val columns = ceil(r.width / (4f * density)).toInt().coerceIn(24, 96)
    val rows = ceil(r.height / (4f * density)).toInt().coerceIn(24, 96)
    val vertices = FloatArray((columns + 1) * (rows + 1) * 2)
    val fields = ArrayList<LensField>((columns + 1) * (rows + 1))
    for (y in 0..rows) for (x in 0..columns) {
        val i = y * (columns + 1) + x
        val point = Offset(r.left + r.width * x / columns, r.top + r.height * y / rows)
        vertices[2 * i] = point.x; vertices[2 * i + 1] = point.y
        fields += field(point)
    }
    val indices = ShortArray(columns * rows * 6)
    var at = 0
    for (y in 0 until rows) for (x in 0 until columns) {
        val a = y * (columns + 1) + x; val b = a + 1; val c = a + columns + 1; val d = c + 1
        indices[at++] = a.toShort(); indices[at++] = b.toShort(); indices[at++] = c.toShort()
        indices[at++] = b.toShort(); indices[at++] = d.toShort(); indices[at++] = c.toShort()
    }
    return LensMeshGeometry(vertices, fields, indices)
}
