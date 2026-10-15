package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class BoundaryOpticsTest {
    private val profile = BoundaryOptics()

    @Test fun rejectsFoldHeavyAndNonFiniteCoefficients() {
        for (invalid in listOf({ BoundaryOptics(strength = 1.67f, falloff = .103f) },
            { BoundaryOptics(strength = Float.NaN) }, { BoundaryOptics(coreEnd = .2f) })) {
            assertThrows(IllegalArgumentException::class.java) { invalid() }
        }
    }

    @Test fun trueBoundarySupportPreservesCoreAndMirrorSymmetry() {
        for ((w, h, r) in listOf(Triple(200f, 200f, 100f), Triple(400f, 140f, 70f), Triple(400f, 300f, 28f))) {
            val shape = LensGeometry(Rect(0f, 0f, w, h), r)
            assertEquals(Offset.Zero, profile.offset(shape, shape.bounds.center))
            for (i in 0..100) for (j in 0..100) {
                val p = Offset(w * i / 100, h * j / 100)
                val a = profile.offset(shape, p)
                val b = profile.offset(shape, Offset(w - p.x, h - p.y))
                assertEquals(-a.x, b.x, .0001f)
                assertEquals(-a.y, b.y, .0001f)
                assertTrue(a.getDistance() <= r * profile.strength + .0001f)
            }
        }
        assertEquals(Offset.Zero, profile.offset(LensGeometry(Rect(0f, 0f, 200f, 200f), 0f), Offset(1f, 1f)))
    }

    @Test fun inverseLookupHasPositiveJacobianAcrossCircleAndRoundedPanel() {
        for (shape in listOf(LensGeometry(Rect(0f, 0f, 200f, 200f), 100f),
            LensGeometry(Rect(0f, 0f, 400f, 140f), 70f),
            LensGeometry(Rect(0f, 0f, 400f, 300f), 28f))) {
            fun lookup(p: Offset) = p + profile.offset(shape, p)
            val epsilon = .05f
            for (x in 1 until shape.bounds.width.toInt() step 3)
                for (y in 1 until shape.bounds.height.toInt() step 3) {
                    val p = Offset(x.toFloat(), y.toFloat())
                    if (shape.field(p).distance < 1f) continue
                    val dx = (lookup(p + Offset(epsilon, 0f)) - lookup(p - Offset(epsilon, 0f))) / (2 * epsilon)
                    val dy = (lookup(p + Offset(0f, epsilon)) - lookup(p - Offset(0f, epsilon))) / (2 * epsilon)
                    assertTrue("Positive inverse Jacobian at $p in $shape: $dx $dy", dx.x * dy.y - dx.y * dy.x > .05f)
                }
        }
    }

    @Test fun outerUnmeasuredBandUsesFiniteSmoothContinuation() {
        val shape = LensGeometry(Rect(0f, 0f, 200f, 200f), 100f)
        val edge = profile.offset(shape, Offset(0f, 100f)).x
        val outside = profile.offset(shape, Offset(-.1f, 100f)).x
        assertEquals(edge, outside, .0001f)
        assertTrue(edge > 0f && edge < 100f * profile.strength)
        for (i in 1..30) {
            val x = i / 10f
            assertTrue(kotlin.math.abs(profile.offset(shape, Offset(x, 100f)).x - edge) < .001f)
        }
    }
}
