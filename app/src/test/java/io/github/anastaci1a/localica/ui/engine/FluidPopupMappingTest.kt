package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test

class FluidPopupMappingTest {
    private val curve = Curve.linear(0f, 1f)
    private fun spring(law: SpringLaw, from: Double = 0.0, to: Double = 1.0) =
        Damped(from, 0.0, to, law.omega.toDouble(), law.zeta.toDouble(), 1.0, 1.0 / 120)
    private fun bits(expected: Double, actual: Double) = assertEquals(expected.toBits(), actual.toBits())

    @Test fun zeroAndFullSharesKeepTheOriginalValueAndVelocityArithmeticBitExact() {
        val main = spring(StockPopupMotion.open)
        val second = spring(StockPopupMotion.crossOpen)
        val residual = Damped(13.125, -31.75, 0.0, 20.0, 1.0, 1000.0, 1.0 / 120)
        for (geometry in listOf(curve, Curve.ramp(.25f, .05f))) for (delta in listOf(-700.25, -0.0, 0.0, 137.125)) {
            for (snap in listOf(false, true)) {
                val selected = if (snap) second else main
                val mapped = MappedEdge(main, geometry, 317.375, delta, residual, second, if (snap) delta else 0.0)
                for (ms in 0..1000) {
                    val t = ms / 1000.0
                    bits(317.375 + delta * geometry.at(selected.value(t).toFloat()) + residual.value(t), mapped.value(t))
                    bits(delta * geometry.slope(selected.value(t).toFloat()) * selected.velocity(t) + residual.velocity(t), mapped.velocity(t))
                }
            }
        }
    }

    @Test fun partialSharesSplitCentreAndSizeWithoutScalingTheSizeChannel() {
        val main = spring(StockPopupMotion.open)
        val second = spring(StockPopupMotion.close)
        val top = MappedEdge(main, curve, 100.0, -100.0, Rest(0.0), second, 150.0)
        val bottom = MappedEdge(main, curve, 200.0, 400.0, Rest(0.0), second, 150.0)
        for (ms in 0..1000) {
            val t = ms / 1000.0
            val p = curve.at(main.value(t).toFloat()).toDouble()
            val q = curve.at(second.value(t).toFloat()).toDouble()
            assertEquals(150.0 + 150.0 * q, (top.value(t) + bottom.value(t)) / 2, 1e-10)
            assertEquals(100.0 + 500.0 * p, bottom.value(t) - top.value(t), 1e-10)
        }
    }

    @Test fun closeFromRestUsesTheSameLawOnBothChannels() {
        val main = spring(StockPopupMotion.close, 1.0, 0.0)
        val second = spring(StockPopupMotion.close, 1.0, 0.0)
        val mapped = MappedEdge(main, curve, 100.0, -100.0, Rest(0.0), second, 150.0)
        for (ms in 0..1000) {
            val t = ms / 1000.0
            bits(main.value(t), second.value(t)); bits(main.velocity(t), second.velocity(t))
            assertEquals(100.0 - 100.0 * curve.at(main.value(t).toFloat()), mapped.value(t), 1e-4)
        }
    }

    @Test fun selectedStyleFlowsThroughTheSpecAndEveryCoveredPopupOwner() {
        assertEquals(PopupMotionStyle.EdgeSpring, InterfaceSettings().popupMotion)
        for (style in PopupMotionStyle.entries) {
            val spec = (uiSpec(InterfaceSettings(popupMotion = style)) as SpecResult.Accepted).spec
            assertEquals(style == PopupMotionStyle.Fluid, spec.popupMotion.centre != null)
            for (kind in PopupKind.entries.filter { it != PopupKind.Legacy }) EngineHarness().use { h ->
                val basis = ExpansionBasis(Rect(10f, 10f, 40f, 40f), Rect(0f, 0f, 300f, 600f), 1f)
                val rig = PopupRig(h, basis, kind, model = spec.popupMotion)
                assertEquals(style == PopupMotionStyle.Fluid || kind == PopupKind.BottomMenu, rig.owner.crossPhase() != null)
                rig.owner.target(true); h.advance(2.0)
                assertEquals(basis.endBounds, rig.rect!!.valueAt(h.clock.stampNow))
                rig.owner.target(false); h.advance(2.0)
                assertEquals(basis.startBounds, rig.rect.valueAt(h.clock.stampNow))
                rig.owner.dispose()
            }
        }
    }

    @Test fun fixedLayoutRemainsFullSizeWhileTheMovingBodyCropsIt() {
        for (width in 1..300 step 17) for (height in 1..600 step 29) {
            assertEquals(1f, popupContentScale(width, height, 300, 600, true), 0f)
            assertEquals(minOf(width / 300f, height / 600f), popupContentScale(width, height, 300, 600, false), 0f)
        }
    }
}
