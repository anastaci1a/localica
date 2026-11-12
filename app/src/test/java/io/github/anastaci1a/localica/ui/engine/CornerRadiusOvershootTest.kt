package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.playerArtworkCornerRadius
import io.github.anastaci1a.localica.ui.popupCornerRadius
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test

class CornerRadiusOvershootTest {
    @Test fun popupLawKeepsCornersNonnegativeThroughOvershootReversalAndClose() {
        val model = StockPopupMotion
        for (hz in listOf(60, 120)) {
            EngineHarness().use { h ->
                val basis = ExpansionBasis(Rect(300f, 100f, 400f, 150f), Rect(0f, 100f, 400f, 600f), 1f)
                val rig = PopupRig(h, basis, PopupKind.Menu, model = model, bound = false)
                val borrowed = GlassExpansion()
                var minimum = 0f
                var maximum = 0f
                fun frames(count: Int) = repeat(count) {
                    h.advance(1.0 / hz)
                    val p = rig.owner.phase.value()
                    val bounded = p.coerceIn(0f, 1f)
                    assertEquals(bounded.toRawBits(), rig.owner.boundedProgress().toRawBits())
                    minimum = minOf(minimum, p); maximum = maxOf(maximum, p)
                    for (curve in listOf(Curve.linear(0f, 1f), Curve.ramp(model.preparedRamp, model.rampBlend), Curve.window(.25f, 1f))) {
                        val g = curve.at(p)
                        borrowed.bind(rig.owner, curve)
                        val before = g.coerceIn(0f, 1f)
                        assertEquals(before.toRawBits(), rig.owner.boundedProgress(curve).toRawBits())
                        assertEquals(before.toRawBits(), borrowed.value.toRawBits())
                        for (home in listOf(0.dp, .001f.dp, 24.dp, 48.dp)) {
                            val radius = popupCornerRadius(home, 28.dp, g)
                            assertTrue("popup radius=$radius phase=$p geometry=$g home=$home", radius.value >= 0f)
                        }
                    }
                    for (background in listOf(0f, .5f, 1f)) {
                        val radius = playerArtworkCornerRadius(p, background)
                        assertTrue("artwork radius=$radius phase=$p background=$background", radius.value >= 0f)
                    }
                }
                rig.owner.target(true); frames(hz / 2) // Includes the open overshoot.
                rig.owner.target(false); frames(hz / 12)
                rig.owner.target(true); frames(hz / 2) // Reverse the moving close.
                // Seed an adversarial inherited closing state within the admitted band.
                // This is a robustness witness, not a claimed reproduction of the device path.
                // The stock critical close with v < -omega*x crosses zero without leaving the band.
                val phase = rig.owner.main
                phase.commitDrive(phase.prepareDrive(.01f, h.clock.stampNow, velocityOverride = -.4))
                rig.owner.target(false); frames(hz)
                assertTrue("open overshoot observed: $maximum", maximum > 1f)
                assertTrue("sub-zero close observed: $minimum", minimum < 0f)
                assertEquals(0f, rig.owner.phase.value(), 0f)
                rig.owner.dispose()
            }
        }
    }

    @Test fun boundedCornerInterpolationIsBitIdenticalInsideTheEndpoints() {
        for (i in 0..10000) {
            val p = i / 10000f
            for (home in listOf(0.dp, .001f.dp, 24.dp, 48.dp)) {
                val before = home * (1f - p) + 28.dp * p
                assertEquals(before.value.toRawBits(), popupCornerRadius(home, 28.dp, p).value.toRawBits())
            }
            for (background in listOf(0f, .5f, 1f)) {
                val before = (8f + 16f * p).dp * (1f - p * background)
                assertEquals(before.value.toRawBits(), playerArtworkCornerRadius(p, background).value.toRawBits())
            }
        }
    }

    @Test fun popupCornersStayWithinTheirEndpointsAcrossTheWholeBand() {
        for (i in 0..11000) {
            val p = -.05f + i / 10000f
            for (home in listOf(0.dp, .001f.dp, 24.dp, 48.dp)) {
                val radius = popupCornerRadius(home, 28.dp, p).value
                assertTrue("popup radius=$radius phase=$p", radius >= minOf(home.value, 28f) && radius <= maxOf(home.value, 28f))
            }
        }
    }

    @Test fun playerArtworkCornersStayWithinTheirEndpointsAcrossTheWholeBand() {
        for (i in 0..11000) {
            val p = -.05f + i / 10000f
            for (background in listOf(0f, .5f, 1f)) {
                val radius = playerArtworkCornerRadius(p, background).value
                assertTrue("artwork radius=$radius phase=$p", radius >= 0f && radius <= 24f)
            }
        }
    }
}
