package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** Pins the shared refraction to its reference formulas in Double, independently written. */
class GlassRefractionTest {
    private val densities = listOf(2f, 2.48125f, 3f)

    private fun profile(amount: Double, height: Double, inside: Double): Double {
        val t = (inside / height).coerceIn(0.0, 1.0)
        return amount * (1 - sqrt(t * (2 - t)))
    }

    @Test fun profileLookupAndOuterMixMatchTheReferenceAtEachDensity() {
        for (density in densities) for (resolution in listOf(1f, .5f)) {
            val unit = density * resolution
            val u = unit.toDouble()
            for (step in -8..120) for (angle in 0 until 360 step 30) {
                val inside = step * .125f * unit
                val a = angle * PI / 180
                val inward = Offset(cos(a).toFloat(), sin(a).toFloat())
                val actual = GlassRefractionReference.at(LensField(inside, inward * 3f), unit)
                val inner = profile(-38.4 * u, 12 * u, inside.toDouble())
                val outer = profile(9.6 * u, 6 * u, inside.toDouble())
                val tolerance = 2e-5 * 38.4 * u
                assertEquals("inner x at $density/$resolution/$step/$angle", -inner * cos(a), actual.inner.x.toDouble(), tolerance)
                assertEquals(-inner * sin(a), actual.inner.y.toDouble(), tolerance)
                assertEquals(-outer * cos(a), actual.outer.x.toDouble(), tolerance)
                assertEquals(-outer * sin(a), actual.outer.y.toDouble(), tolerance)
                assertEquals(.3 * ((u - inside) / (.5 * u)).coerceIn(0.0, 1.0), actual.outerWeight.toDouble(), 1e-6)
            }
        }
    }

    @Test fun rimAndBandValuesInDevicePixels() {
        for (density in densities) {
            val rim = GlassRefractionReference.at(LensField(0f, Offset(0f, 1f)), density)
            // The inner sample is taken inward and the outer one outward.
            assertEquals(38.4f * density, rim.inner.y, 1e-4f * density)
            assertEquals(-9.6f * density, rim.outer.y, 1e-4f * density)
            assertEquals(.3f, rim.outerWeight, 0f)
            val band = GlassRefractionReference.at(LensField(12f * density, Offset(0f, 1f)), density)
            assertEquals(0f, band.inner.getDistance(), 0f)
            assertEquals(0f, band.outerWeight, 0f)
            assertEquals(.15f, GlassRefractionReference.at(LensField(.75f * density, Offset(1f, 0f)), density).outerWeight, 1e-6f)
        }
        // The A35 at 397 dpi records at half resolution behind the default blur.
        assertEquals(38.4 * 2.48125 * .5, GlassRefractionReference.at(LensField(0f, Offset(1f, 0f)), 2.48125f * .5f).inner.x.toDouble(), 1e-4)
    }


    @Test fun roundedRectangleFieldMatchesTheClippedOutline() {
        var skipped = 0
        for ((size, corner) in listOf(Offset(120f, 48f) to 24f, Offset(88f, 44f) to 22f, Offset(324f, 64f) to 24f,
            Offset(300f, 100f) to 28f, Offset(100f, 100f) to 50f, Offset(200f, 64f) to 0f)) for (scale in densities) {
            val shape = LensGeometry(Rect(0f, 0f, size.x * scale, size.y * scale), corner * scale)
            val r = (corner * scale).toDouble()
            for (x in 0..48) for (y in 0..24) {
                val p = Offset(shape.bounds.width * x / 48f, shape.bounds.height * y / 24f)
                val local = p - shape.bounds.center
                val dx = abs(local.x.toDouble()) - shape.bounds.width / 2.0 + r
                val dy = abs(local.y.toDouble()) - shape.bounds.height / 2.0 + r
                val length = hypot(max(dx, 0.0), max(dy, 0.0))
                val distance = r - length - min(0.0, max(dx, dy))
                val actual = shape.field(p)
                val where = "$size@$scale ($x,$y)"
                assertEquals(where, distance, actual.distance.toDouble(), 2e-4 * shape.span)
                if (if (length > 0.0) length < 1e-3 * shape.span else abs(dx - dy) < 1e-3 * shape.span) {
                    skipped++
                    continue
                }
                val nx = if (length > 0.0) max(dx, 0.0) / length else if (dx > dy) 1.0 else 0.0
                val ny = if (length > 0.0) max(dy, 0.0) / length else if (dx > dy) 0.0 else 1.0
                assertEquals(where, if (local.x < 0f) nx else -nx, actual.inward.x.toDouble(), 1e-4)
                assertEquals(where, if (local.y < 0f) ny else -ny, actual.inward.y.toDouble(), 1e-4)
            }
        }
        assertTrue("skipped $skipped", skipped < 36 * 49 * 25 / 50)
    }

    /** The exact rounded corner reaches its medial tie at a depth equal to its radius. */
    @Test fun diagonalSeamEntersTheProfileOnlyBelowItsHeight() {
        for (corner in listOf(12f, 24f)) {
            val shape = LensGeometry(Rect(0f, 0f, 200f, 120f), corner)
            for (x in 0 until 200) for (y in 0 until 120) {
                val here = shape.field(Offset(x + .5f, y + .5f))
                if (here.distance >= GlassRefraction.InnerHeight) continue
                for (next in listOf(shape.field(Offset(x + 1.5f, y + .5f)), shape.field(Offset(x + .5f, y + 1.5f))))
                    if (next.distance < GlassRefraction.InnerHeight)
                        assertTrue("corner=$corner ($x,$y)", here.inward.x * next.inward.x + here.inward.y * next.inward.y > .7f)
            }
        }
        val smallCorner = LensGeometry(Rect(0f, 0f, 200f, 120f), 10f)
        assertTrue(smallCorner.field(Offset(10f, 10f)).distance < GlassRefraction.InnerHeight)
        val square = LensGeometry(Rect(0f, 0f, 200f, 120f), 0f)
        val left = square.field(Offset(5f, 5.5f)).inward
        val top = square.field(Offset(5.5f, 5f)).inward
        assertEquals(Offset(1f, 0f), left)
        assertEquals(Offset(0f, 1f), top)
    }

    @Test fun capsuleEndsAndStraightSidesShareTheTrueDistance() {
        val capsule = LensGeometry(Rect(0f, 0f, 120f, 48f), 24f)
        val cap = capsule.bodies.single()
        assertEquals(24f, cap.topLeftCornerRadius.x, 0f)
        assertEquals(24f, cap.topLeftCornerRadius.y, 1e-4f)
        for (x in listOf(-20f, 0f, 20f)) for (inside in listOf(0f, 1f, 6f, 12f)) {
            val field = capsule.field(capsule.bounds.center + Offset(x, 24f - inside))
            assertEquals(inside, field.distance, 1e-4f)
            assertEquals(0f, field.inward.x, 0f)
            assertEquals(-1f, field.inward.y, 0f)
        }
        assertEquals(24f, capsule.field(capsule.bounds.center).distance, 1e-4f)
    }
}
