package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln

/** #640 §1.5 / H3: o(d) = k·ln(1 + d/k) starts locked to the finger and then resists. */
class LogFollowTest {
    private val k = 12f

    @Test fun zeroAtRestAndSlopeOneAtTheOrigin() {
        assertEquals(0f, LogFollow.offset(0f, k), 0f)
        assertEquals(1f, LogFollow.slope(0f, k), 0f)
        // Numerical derivative at the origin.
        val h = 1e-3f
        assertEquals(1f, LogFollow.offset(h, k) / h, 1e-3f)
    }

    @Test fun matchesTheDesignTable() {
        assertEquals(19.7f, LogFollow.offset(50f, k), .05f)
        assertEquals(34.5f, LogFollow.offset(200f, k), .05f)
        assertEquals(42.4f, LogFollow.offset(400f, k), .05f)
        // Fallback k = 8 dp resists sooner.
        assertEquals(8f * ln(1 + 50f / 8f), LogFollow.offset(50f, 8f), 1e-4f)
    }

    @Test fun monotoneConcaveAndBelowTheFinger() {
        var previous = 0f; var previousSlope = 1f
        var d = 0f
        while (d < 2000f) {
            d += 2.5f
            val o = LogFollow.offset(d, k)
            assertTrue("monotone at $d", o > previous)
            assertTrue("never ahead of the finger at $d", o < d)
            val slope = LogFollow.slope(d, k)
            assertTrue("concave at $d", slope < previousSlope)
            // Analytic slope equals the finite difference.
            // Midpoint rule: error ≤ step²/24 · max|o‴| = 2.5²/24 · 2/k² ≈ 3.6e-3.
            assertEquals((o - previous) / 2.5f, LogFollow.slope(d - 1.25f, k), 4e-3f)
            previous = o; previousSlope = slope
        }
    }

    @Test fun inverseRoundTrips() {
        for (d in listOf(0f, 1f, 8f, 50f, 200f, 400f, 1500f)) assertEquals(d, LogFollow.inverse(LogFollow.offset(d, k), k), d * 1e-5f + 1e-4f)
    }

    @Test fun vectorFollowIsAlongTheFingerAndContinuousAtZero() {
        val tiny = LogFollow.follow(Offset(1e-4f, -1e-4f), k)
        assertEquals(1e-4f, tiny.x, 1e-7f); assertEquals(-1e-4f, tiny.y, 1e-7f)
        assertEquals(Offset.Zero, LogFollow.follow(Offset.Zero, k))
        val delta = Offset(30f, 40f) // |delta| = 50
        val o = LogFollow.follow(delta, k)
        assertEquals(LogFollow.offset(50f, k), o.getDistance(), 1e-4f)
        assertEquals(delta.x / delta.y, o.x / o.y, 1e-5f)
    }

    @Test fun velocityIsTheDerivativeOfTheFollowAlongAnyPath() {
        val dt = 1e-3f
        val paths = listOf(
            Triple(Offset(50f, 0f), Offset(100f, 0f), "radial"),
            Triple(Offset(50f, 0f), Offset(0f, 100f), "tangential"),
            Triple(Offset(30f, 40f), Offset(-60f, 25f), "mixed"),
            Triple(Offset(0f, 0f), Offset(80f, -20f), "from rest"),
            Triple(Offset(300f, 100f), Offset(200f, 200f), "far"),
        )
        for ((delta, v, name) in paths) {
            val analytic = LogFollow.velocity(delta, v, k)
            val before = LogFollow.follow(delta - Offset(v.x * dt / 2, v.y * dt / 2), k)
            val after = LogFollow.follow(delta + Offset(v.x * dt / 2, v.y * dt / 2), k)
            val numeric = Offset((after.x - before.x) / dt, (after.y - before.y) / dt)
            // Away from rest the central difference is O(dt²); at rest the ratio o(d)/d has slope −1/(2k), so the
            // symmetric difference is biased by |v|²·dt/(4k) and only converges O(dt) to the exact gain of 1.
            val speed = v.getDistance()
            val tolerance = 1e-2f + 2e-4f * speed + (if (delta == Offset.Zero) speed * speed * dt / (4f * k) * 1.5f else 0f)
            assertEquals("$name x", numeric.x, analytic.x, tolerance)
            assertEquals("$name y", numeric.y, analytic.y, tolerance)
        }
        assertEquals(Offset(5f, -7f), LogFollow.velocity(Offset.Zero, Offset(5f, -7f), k))
        // Radial velocity far out is scaled by the slope, so the release spring starts slower than the finger.
        val far = LogFollow.velocity(Offset(400f, 0f), Offset(1000f, 0f), k)
        assertEquals(1000f * LogFollow.slope(400f, k), far.x, 1e-2f)
        assertTrue(abs(far.y) < 1e-4f)
    }

    @Test fun rejectsInvalidInputs() {
        assertThrows(IllegalArgumentException::class.java) { LogFollow.offset(10f, 0f) }
        assertThrows(IllegalArgumentException::class.java) { LogFollow.offset(-1f, k) }
        assertThrows(IllegalArgumentException::class.java) { LogFollow.slope(Float.NaN, k) }
        assertThrows(IllegalArgumentException::class.java) { LogFollow.inverse(-1f, k) }
        assertThrows(IllegalArgumentException::class.java) { LogFollow.follow(Offset(1f, 1f), Float.POSITIVE_INFINITY) }
    }
}
