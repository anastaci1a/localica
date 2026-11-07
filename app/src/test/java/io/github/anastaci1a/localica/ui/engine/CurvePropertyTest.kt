package io.github.anastaci1a.localica.ui.engine

import io.github.anastaci1a.localica.ui.browseViewVisibility
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

/** #600 U01.03: every registered Curve has an analytic slope that matches its values on the whole ±5% band and is
 * continuous at every join; composition, product and mix follow the chain rules; invalid windows are rejected; the
 * window is the legacy Browse visibility smoothstep. */
class CurvePropertyTest {
    private val registered = mutableListOf<Pair<Curve, FloatArray>>()

    @Before fun register() { CurveHooks.created = { curve, joins -> registered += curve to joins } }
    @After fun unregister() { CurveHooks.created = null }

    private fun corpus(): List<Curve> {
        val w = Curve.window(.2f, .6f); val s = Curve.softWindow(.55f, .95f); val l = Curve.linear(-3f, 40f)
        return listOf(w, s, l, Curve.smooth(10f, -4f), Curve.constant(.7f), w + s, w * l, l.compose(s), s.compose(Curve.linear(.1f, .9f)),
            Curve.window(0f, 1f).compose(Curve.linear(-.2f, 1.3f)), w.mix(l, s), w.scaled(.2f), (w * s).compose(Curve.window(.1f, .9f)))
    }

    /** Central difference in double around a Float argument; h is small next to every window in the corpus. */
    private fun numeric(c: Curve, p: Float, h: Float = 1e-3f) = (c.at(p + h).toDouble() - c.at(p - h)) / (2.0 * h)
    /** Second-order one-sided difference (side ±1): exact for the quadratic part, so a join's curvature step cannot leak in. */
    private fun oneSided(c: Curve, p: Float, side: Int, h: Float = 1e-3f) =
        side * (-3.0 * c.at(p) + 4.0 * c.at(p + side * h) - c.at(p + 2 * side * h)) / (2.0 * h)

    @Test fun closedMapsHaveAnalyticSlopeAtAllJoins() {
        corpus()
        assertTrue("every constructed Curve reports itself to the registry hook", registered.size >= 13)
        val random = Random(600)
        for ((curve, joins) in registered) {
            val points = List(256) { -.05f + 1.1f * random.nextFloat() }
            val scale = points.maxOf { kotlin.math.abs(curve.slope(it).toDouble()) }.coerceAtLeast(1.0)
            for (p in points) {
                // Within 2h of a join, difference on the point's own side so the stencil never straddles the join.
                val near = joins.firstOrNull { kotlin.math.abs(p - it) < 2e-3f }
                val estimate = if (near == null) numeric(curve, p) else oneSided(curve, p, if (p < near) -1 else 1)
                val analytic = curve.slope(p).toDouble(); val error = kotlin.math.abs(estimate - analytic)
                assertTrue("slope at $p: analytic $analytic, numeric $estimate",
                    error <= 1e-3 * kotlin.math.abs(analytic) + 1e-3 * scale)
            }
            for (j in joins) {
                val analytic = curve.slope(j).toDouble()
                for (side in listOf(-1, 1)) assertEquals("side $side slope at join $j", analytic, oneSided(curve, j, side),
                    1e-3 * kotlin.math.abs(analytic) + 1e-3 * scale)
                val left = curve.slope(j - 1e-4f).toDouble(); val right = curve.slope(j + 1e-4f).toDouble()
                assertEquals("C1 join at $j", left, right, 1e-2 * scale)
            }
        }
    }

    @Test fun compositionProductAndMixFollowTheChainRules() {
        val a = Curve.window(.1f, .7f); val b = Curve.linear(.3f, 2f); val m = Curve.softWindow(0f, 1f)
        for (p in listOf(-.05f, .2f, .45f, .69f, 1.05f)) {
            assertEquals(a.slope(b.at(p)) * b.slope(p), a.compose(b).slope(p), 1e-5f)
            assertEquals(a.slope(p) * b.at(p) + a.at(p) * b.slope(p), (a * b).slope(p), 1e-5f)
            assertEquals(a.at(p) * (1 - m.at(p)) + b.at(p) * m.at(p), a.mix(b, m).at(p), 1e-5f)
            assertEquals(a.slope(p) * (1 - m.at(p)) - a.at(p) * m.slope(p) + b.slope(p) * m.at(p) + b.at(p) * m.slope(p),
                a.mix(b, m).slope(p), 1e-4f)
            assertEquals(.2f * a.slope(p), a.scaled(.2f).slope(p), 1e-6f)
        }
        // Geometry maps extrapolate affinely on the band; bounded windows hold their endpoints with zero slope.
        assertEquals(-.05f * 40f + 2f, Curve.linear(2f, 42f).at(-.05f), 1e-4f)
        assertEquals(0f, Curve.window(0f, 1f).slope(1.05f), 0f); assertEquals(1f, Curve.window(0f, 1f).at(1.05f), 0f)
    }

    @Test fun invalidWindowsAreRejected() {
        for (bad in listOf<() -> Curve>({ Curve.window(.5f, .5f) }, { Curve.window(.8f, .2f) }, { Curve.softWindow(Float.NaN, 1f) },
            { Curve.softWindow(0f, Float.POSITIVE_INFINITY) }, { Curve.linear(Float.NaN, 1f) }, { Curve.constant(Float.POSITIVE_INFINITY) })) {
            try { bad(); fail("accepted an invalid Curve") } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun windowIsTheLegacyBrowseVisibilitySmoothstep() {
        val fade = .45f; val curve = Curve.window(1f - fade, 1f)
        for (i in 0..200) { val p = i / 200f
            assertEquals("p=$p", browseViewVisibility(false, true, p, fade), curve.at(p), 1e-6f) }
    }
}
