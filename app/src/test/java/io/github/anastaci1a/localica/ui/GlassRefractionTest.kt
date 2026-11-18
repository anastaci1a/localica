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

    /** Transcribed from the recovered continuous-corner helper, in Double. */
    private fun helper(p: DoubleArray, extent: DoubleArray, radius: Double, w: DoubleArray): DoubleArray {
        val k = 1.52866494655609
        val r = abs(radius); val big = r * k; val rho = big + (r - big) * max(w[0], w[1])
        val q = DoubleArray(2) { rho + (p[it] - extent[it]) }
        val v = DoubleArray(2) { abs(max(0.0, (p[it] - extent[it] + big) / big)) }
        val l = hypot(v[0], v[1]); val m = max(v[0], v[1])
        val t = if (m == 0.0) 0.0 else (min(v[0], v[1]) / m).coerceIn(0.0, 1.0)
        val poly = (((3.15600991249084 - t * .926054000854492) * t - 3.64122009277344) * t + 1.26803004741669) * t + .268530994653702
        val a = l + 1 - 1 / (1 - t * t * l.coerceIn(0.0, 1.0) * poly)
        val b = hypot(max(0.0, v[0] * k - .528665006160736), max(0.0, v[1] * k - .528665006160736)) * .65416556596756 + .34583443403244
        val ax = a + (b - a) * w[0]; val ay = a + (b - a) * w[1]
        val sign = if (v[1] > v[0]) 1.0 else -1.0
        val z = ax + (ay - ax) * (.5 - sign + sign * t).coerceIn(0.0, 1.0) - 1
        val n = hypot(max(q[0], 0.0), max(q[1], 0.0))
        val normal = if (n > 0) doubleArrayOf(max(q[0], 0.0) / n, max(q[1], 0.0) / n) else if (q[0] > q[1]) doubleArrayOf(1.0, 0.0) else doubleArrayOf(0.0, 1.0)
        return doubleArrayOf(min(0.0, max(q[0], q[1])) + big * z, normal[0], normal[1], n, q[0] - q[1])
    }

    @Test fun roundedRectangleFieldIsTheContinuousCornerHelper() {
        var skipped = 0
        for ((size, corner) in listOf(Offset(120f, 48f) to 24f, Offset(88f, 44f) to 22f, Offset(324f, 64f) to 24f,
            Offset(300f, 100f) to 28f, Offset(100f, 100f) to 50f, Offset(200f, 64f) to 0f)) for (scale in densities) {
            val shape = LensGeometry(Rect(0f, 0f, size.x * scale, size.y * scale), corner * scale)
            val r = max(corner * scale, .01f).toDouble()
            val w = DoubleArray(2) { side -> ((if (side == 0) size.x else size.y) * scale * -1.44577851758675 / (r * 1.528665) + 2.8915570351735).coerceIn(0.0, 1.0) }
            assertEquals(w[0], shape.weights.x.toDouble(), 1e-6)
            assertEquals(w[1], shape.weights.y.toDouble(), 1e-6)
            for (x in 0..48) for (y in 0..24) {
                val p = Offset(shape.bounds.width * x / 48f, shape.bounds.height * y / 24f)
                val local = p - shape.bounds.center
                val expected = helper(doubleArrayOf(abs(local.x).toDouble(), abs(local.y).toDouble()),
                    doubleArrayOf(shape.bounds.width / 2.0, shape.bounds.height / 2.0), r, w)
                val actual = shape.field(p)
                val where = "$size@$scale ($x,$y)"
                assertEquals(where, -expected[0], actual.distance.toDouble(), 2e-4 * shape.span)
                // Near a zero normalization vector or an axis tie, Float and Double may pick different axes.
                if (if (expected[3] > 0) expected[3] < 1e-3 * shape.span else abs(expected[4]) < 1e-3 * shape.span) { skipped++; continue }
                assertEquals(where, if (local.x < 0f) expected[1] else -expected[1], actual.inward.x.toDouble(), 1e-4)
                assertEquals(where, if (local.y < 0f) expected[2] else -expected[2], actual.inward.y.toDouble(), 1e-4)
            }
        }
        assertTrue("skipped $skipped", skipped < 36 * 49 * 25 / 50)
    }

    /** Registered D3 seam: the axis fallback turns the inward direction 90 degrees across the interior
     * diagonal. It lies at depth reach = 1.5287 r, so it enters the 12 dp profile only below r = 7.85 dp. */
    @Test fun diagonalSeamEntersTheProfileOnlyBelowTheRegisteredRadius() {
        for (corner in listOf(10f, 12f, 24f)) {
            val shape = LensGeometry(Rect(0f, 0f, 200f, 120f), corner)
            for (x in 0 until 200) for (y in 0 until 120) {
                val here = shape.field(Offset(x + .5f, y + .5f))
                if (here.distance >= GlassRefraction.InnerHeight) continue
                for (next in listOf(shape.field(Offset(x + 1.5f, y + .5f)), shape.field(Offset(x + .5f, y + 1.5f))))
                    if (next.distance < GlassRefraction.InnerHeight)
                        assertTrue("corner=$corner ($x,$y)", here.inward.x * next.inward.x + here.inward.y * next.inward.y > .7f)
            }
        }
        val square = LensGeometry(Rect(0f, 0f, 200f, 120f), 0f)
        val left = square.field(Offset(5f, 5.5f)).inward
        val top = square.field(Offset(5.5f, 5f)).inward
        assertEquals(Offset(1f, 0f), left)
        assertEquals(Offset(0f, 1f), top)
    }

    @Test fun capsuleWeightsRoundOnlyTheShortAxisAndStraightSidesAreTrueDistance() {
        val capsule = LensGeometry(Rect(0f, 0f, 120f, 48f), 24f)
        assertEquals(0f, capsule.weights.x, 0f)
        assertEquals(1f, capsule.weights.y, 1e-4f)
        for (x in listOf(-20f, 0f, 20f)) for (inside in listOf(0f, 1f, 6f, 12f)) {
            val field = capsule.field(capsule.bounds.center + Offset(x, 24f - inside))
            assertEquals(inside, field.distance, 1e-4f)
            assertEquals(0f, field.inward.x, 0f)
            assertEquals(-1f, field.inward.y, 0f)
        }
        assertEquals(24f, capsule.field(capsule.bounds.center).distance, 1e-4f)
    }
}
