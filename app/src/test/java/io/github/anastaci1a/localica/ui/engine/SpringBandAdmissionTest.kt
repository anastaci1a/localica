package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test

/** #854 review S1 and S2 (lead rulings 2026-11-11): a model is admitted only if every single open/close reversal stays in the live band,
 * admission and the engine's extremum proofs read that same band, and a release build counts an unproven extremum instead of throwing. */
class SpringBandAdmissionTest {
    private val m = StockPopupMotion
    private val band = StockUiSpec.expansion.band.toDouble()
    private val basis = ExpansionBasis(Rect(300f, 100f, 400f, 150f), Rect(0f, 100f, 400f, 600f), 1f)

    @Test fun stockReversalsStayInTheBandAndReachExactlyTheOpenOvershoot() {
        val reach = popupReversalReach(m)
        for ((pair, r) in reach) assertTrue("$pair $r", r.start >= -band && r.endInclusive <= 1 + band)
        // A reversal into the open never exceeds the open's own overshoot from rest (agent-188: 1.01992 = kappa(.78)).
        assertEquals(1 + springOvershoot(m.open.zeta.toDouble()), reach.getValue("closeOpen").endInclusive, 1e-4)
        // The closed-form extrema agree with a dense sampling of the same spring.
        for ((x, v) in listOf(.3 to -4.0, .05 to -1.0, .9 to 6.0, .5 to 0.0)) for (law in listOf(m.open, m.close, m.crossOpen)) {
            val w = law.omega.toDouble(); val z = law.zeta.toDouble(); val e = springExtrema(x, v, 1.0, w, z)
            val sampled = (0..200_000).map { springState(x, v, 1.0, w, z, it * 1e-5).first }
            assertEquals("min $x $v $law", sampled.min(), e.start, 1e-6); assertEquals("max $x $v $law", sampled.max(), e.endInclusive, 1e-6)
        }
    }

    @Test fun anOutOfBandOverridePairIsRejected() {
        // Each law alone is admissible from rest (open overshoot kappa(.78) = 2 %), but a fast open reversing a close leaves the band.
        val fast = m.copy(open = SpringLaw(100f, .78f))
        assertTrue(springOvershoot(.78) <= band)
        val reasons = popupMotionAdmission(fast, band.toFloat())
        assertTrue(reasons.toString(), reasons.isNotEmpty() && reasons.all { it.startsWith("reversal.") })
        val result = uiSpec(InterfaceSettings(), UiSpecData(popupMotion = fast))
        assertTrue(result is SpecResult.Rejected && result.reasons.any { it.startsWith("popupMotion.reversal.") })
        assertTrue(uiSpec(InterfaceSettings(), UiSpecData(popupMotion = m)) is SpecResult.Accepted)
    }

    @Test fun admissionAndTheEngineReadTheSameLiveBand() {
        // A band below the open overshoot is rejected by admission rather than thrown later by the engine.
        val tight = StockUiSpec.expansion.copy(band = (springOvershoot(m.open.zeta.toDouble()) * .75).toFloat())
        val rejected = uiSpec(InterfaceSettings(), UiSpecData(expansion = tight))
        assertTrue(rejected is SpecResult.Rejected && (rejected as SpecResult.Rejected).reasons.any { it.startsWith("popupMotion.") })
        // An admitted band override flies reversals in debug without a band mismatch.
        val narrow = StockUiSpec.expansion.copy(band = .03f)
        val spec = (uiSpec(InterfaceSettings(), UiSpecData(expansion = narrow)) as SpecResult.Accepted).spec
        assertTrue(SpringBandTelemetry.strict)
        EngineHarness(spec = spec).use { h ->
            val owner = ExpandCollapse(h.clock, spec.expansion, false).also { it.usePopupLaw(spec.popupMotion, PopupKind.Menu); it.setBasis(basis) }
            val rect = MotionRect(h.clock, basis.startBounds, EdgeRanges(4000f, 4000f, 4000f, 4000f)); owner.bind(rect)
            for (switch in listOf(.02, .05, .09, .14, .3)) { owner.target(true); h.advance(switch); owner.target(false); h.advance(switch / 2); owner.target(true); h.advance(2.0) }
            assertTrue(owner.retirementReady); owner.dispose()
        }
    }

    @Test fun aReleaseBuildCountsAnUnprovenExtremumInsteadOfThrowing() {
        val strict = SpringBandTelemetry.strict; val before = SpringBandTelemetry.violations
        // Bypass admission on purpose: the engine alone must keep the motion and record the violation.
        val unadmitted = m.copy(open = SpringLaw(100f, .78f))
        try {
            SpringBandTelemetry.strict = false
            EngineHarness().use { h ->
                val owner = ExpandCollapse(h.clock, StockUiSpec.expansion, false).also { it.usePopupLaw(unadmitted, PopupKind.Menu); it.setBasis(basis) }
                // A fast open reversed early by the close overshoots far past 1 + band (agent-188: up to 1.216).
                owner.target(true); h.advance(.01); owner.target(false)
                assertTrue("the motion runs unchanged", !owner.phase.settled)
                h.advance(2.0); assertTrue(owner.phase.settled); owner.dispose()
            }
            assertTrue("violations recorded", SpringBandTelemetry.violations > before); assertNotNull(SpringBandTelemetry.last)
            SpringBandTelemetry.strict = true
            EngineHarness().use { h ->
                val owner = ExpandCollapse(h.clock, StockUiSpec.expansion, false).also { it.usePopupLaw(unadmitted, PopupKind.Menu); it.setBasis(basis) }
                owner.target(true); h.advance(.01)
                assertThrows(IllegalArgumentException::class.java) { owner.target(false) }
            }
        } finally { SpringBandTelemetry.strict = strict }
    }
}
