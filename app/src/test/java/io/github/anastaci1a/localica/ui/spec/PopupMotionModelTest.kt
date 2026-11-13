package io.github.anastaci1a.localica.ui.spec

import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.engine.Damped
import io.github.anastaci1a.localica.ui.engine.SpringOracle
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** #854 T1: the stock model is its provenance record, its derived times and overshoot follow the closed forms, and admission rejects every section 3.4 violation. */
class PopupMotionModelTest {
    private val m = StockPopupMotion
    private fun laws(model: PopupMotionModel) = mapOf("open" to model.open, "close" to model.close, "crossOpen" to model.crossOpen, "gate" to model.gate)
    private fun with(key: String, law: SpringLaw) = when (key) {
        "open" -> m.copy(open = law); "close" -> m.copy(close = law); "crossOpen" -> m.copy(crossOpen = law); else -> m.copy(gate = law)
    }
    private fun rejects(reason: String, model: PopupMotionModel) {
        assertTrue("$reason in ${popupMotionAdmission(model)}", reason in popupMotionAdmission(model))
        val result = uiSpec(InterfaceSettings(), UiSpecData(popupMotion = model))
        assertTrue("uiSpec must reject $reason: $result", result is SpecResult.Rejected && "popupMotion.$reason" in result.reasons)
    }

    @Test fun stockModelEqualsItsProvenanceRecord() {
        assertEquals(StockPopupMotionRevision, m.revision)
        assertEquals(setOf("open", "close", "crossOpen", "gate", "snapAspect", "preparedRamp"), StockPopupMotionRecord.keys)
        for ((key, law) in laws(m)) assertEquals(key, StockPopupMotionRecord.getValue(key), listOf(law.omega, law.zeta))
        assertEquals(StockPopupMotionRecord.getValue("snapAspect"), listOf(m.snapAspect))
        assertEquals(StockPopupMotionRecord.getValue("preparedRamp"), listOf(m.preparedRamp, m.rampBlend))
        assertEquals("The native-gate approach is the close law", m.close, m.gate)
        assertEquals(PopupKind.entries.toSet(), m.variants.keys)
        // 855-ios262-60fps-interim: the 26.2 open is the livelier spring (about twice as fast as the WWDC25 captures) and the close is slower than it by t90.
        for (f in listOf(.5, .9)) assertTrue("open t$f", SpringOracle.crossing(f, m.open.omega.toDouble(), m.open.zeta.toDouble()) < SpringOracle.crossing(f, m.close.omega.toDouble(), m.close.zeta.toDouble()))
        assertEquals("855-ios262-60fps-interim-r926", m.revision)
        assertTrue("The cross axis reaches 90 % within one 30 fps frame", SpringOracle.crossing(.9, m.crossOpen.omega.toDouble(), m.crossOpen.zeta.toDouble()) <= 1.0 / 30)
    }

    @Test fun lawLookupReadsOnlyThroughTheModel() {
        for (kind in PopupKind.entries) {
            assertEquals(m.open, m.law(kind, PopupDirection.Open, false)); assertEquals(m.close, m.law(kind, PopupDirection.Close, false)); assertEquals(m.gate, m.law(kind, PopupDirection.Gate, false))
            assertEquals(m.crossOpen, m.law(kind, PopupDirection.Open, true)); assertEquals(m.close, m.law(kind, PopupDirection.Close, true)); assertEquals(m.gate, m.law(kind, PopupDirection.Gate, true))
        }
    }

    @Test fun derivedTimesAndOvershootMatchTheClosedFormsAtEverySpeed() {
        for ((key, law) in laws(m)) for (s in (5..15).map { it / 10.0 }) {
            val w = law.omega * s; val z = law.zeta.toDouble(); val spring = Damped(0.0, 0.0, 1.0, w, z, 1.0, 1.0 / 120)
            for (f in listOf(.5, .9, .99)) {
                val t = SpringOracle.crossing(f, w, z)
                assertEquals("$key s=$s t$f", f, spring.raw(t, false), 1e-9)
                // Every time scales by 1/s while zeta and the overshoot are unchanged.
                assertEquals("$key s=$s t$f scaling", SpringOracle.crossing(f, law.omega.toDouble(), z) / s, t, 1e-12)
            }
            val kappa = exp(-PI * z / sqrt(1 - z * z)).takeIf { z < 1 } ?: 0.0
            assertEquals(key, kappa, springOvershoot(z), 1e-15)
            if (z < 1) {
                assertEquals("$key peak", 1 + kappa, spring.raw(PI / (w * sqrt(1 - z * z)), false), 1e-9)
                assertEquals("$key exact extremum", 1 + kappa, spring.extrema.endInclusive, 1e-12)
            } else assertTrue("$key never overshoots", spring.extrema.endInclusive <= 1 + SettleThreshold)
        }
        assertTrue("The stock open overshoot fits the band", springOvershoot(m.open.zeta.toDouble()) <= StockPopupExpansionBand)
    }

    @Test fun admissionAcceptsStockAndTheSettleHorizonScalesWithSpeed() {
        assertEquals(emptyList<String>(), popupMotionAdmission(m))
        val spec = (uiSpec(InterfaceSettings()) as SpecResult.Accepted).spec
        assertEquals(m, spec.popupMotion); assertEquals(1f, spec.speed, 0f)
        for ((key, law) in laws(m)) {
            val slowest = popupLawHorizon(law, MinimumAnimationSpeed.toDouble()); assertTrue("$key $slowest", slowest <= 2.0)
            // The settle thresholds are absolute (position and velocity per second), so the horizon falls with speed but not exactly as 1/s.
            val horizons = listOf(.5, .75, 1.0, 1.5).map { popupLawHorizon(law, it) }
            assertTrue("$key horizon falls with speed $horizons", horizons.zipWithNext().all { (a, b) -> b < a })
            assertTrue("$key horizon stays near 1/s $horizons", listOf(.5, .75, 1.0, 1.5).zip(horizons).all { (s, t) -> abs(t * s / horizons[2] - 1) < .1 })
        }
    }

    @Test fun admissionRejectsEveryLawOutOfItsRange() {
        for (key in laws(m).keys) for (law in listOf(SpringLaw(0f, 1f), SpringLaw(.99f, 1f), SpringLaw(400.5f, 1f), SpringLaw(Float.NaN, 1f), SpringLaw(Float.POSITIVE_INFINITY, 1f),
            SpringLaw(20f, .49f), SpringLaw(20f, 4.01f), SpringLaw(20f, Float.NaN), SpringLaw(20f, Float.POSITIVE_INFINITY))) rejects(key, with(key, law))
    }

    @Test fun admissionPinsOpenOvershootToTheBandAndEveryOtherLawToNoOvershoot() {
        // The damping ratio at which the open overshoot equals the band, from the closed form.
        var lo = .5; var hi = .999; repeat(100) { val mid = (lo + hi) / 2; if (SpringOracle.kappa(mid) > StockPopupExpansionBand) lo = mid else hi = mid }
        val edge = hi
        rejects("open.overshoot", m.copy(open = SpringLaw(m.open.omega, (edge - .01).toFloat())))
        assertFalse("open.overshoot" in popupMotionAdmission(m.copy(open = SpringLaw(m.open.omega, (edge + .01).toFloat()))))
        rejects("open.overshoot", m.copy(open = SpringLaw(m.open.omega, .5f)))
        for (key in listOf("close", "crossOpen", "gate")) for (zeta in listOf(.5f, .9f, .999f)) rejects("$key.overshoot", with(key, SpringLaw(laws(m).getValue(key).omega, zeta)))
        for (key in listOf("close", "crossOpen", "gate")) assertFalse(popupMotionAdmission(with(key, SpringLaw(laws(m).getValue(key).omega, 2f))).any { it.endsWith("overshoot") })
    }

    @Test fun admissionPinsTheSettleHorizonAtTheSlowestSpeed() {
        for (key in laws(m).keys) {
            val law = laws(m).getValue(key)
            rejects("$key.horizon", with(key, SpringLaw(1f, law.zeta)))
            // The omega at which the horizon at s = .5 is 2 s, by bisection on the horizon itself.
            var lo = 1f; var hi = law.omega; repeat(60) { val mid = (lo + hi) / 2; if (popupLawHorizon(SpringLaw(mid, law.zeta), MinimumAnimationSpeed.toDouble()) > 2.0) lo = mid else hi = mid }
            assertFalse("$key just above", "$key.horizon" in popupMotionAdmission(with(key, SpringLaw(hi * 1.01f, law.zeta))))
            assertTrue("$key just below", "$key.horizon" in popupMotionAdmission(with(key, SpringLaw(lo * .99f, law.zeta))))
        }
    }

    @Test fun admissionRejectsBadSnapRampAndVariants() {
        for (aspect in listOf(1f, .9f, 0f, Float.NaN, Float.POSITIVE_INFINITY)) rejects("snapAspect", m.copy(snapAspect = aspect))
        assertFalse("snapAspect" in popupMotionAdmission(m.copy(snapAspect = 1.01f)))
        for (p0 in listOf(-.1f, .51f, Float.NaN)) rejects("ramp", m.copy(preparedRamp = p0))
        for (b in listOf(0f, -.05f, .26f, Float.NaN)) rejects("ramp", m.copy(rampBlend = b))
        assertFalse("ramp" in popupMotionAdmission(m.copy(preparedRamp = 0f, rampBlend = .25f)))
        assertFalse("ramp" in popupMotionAdmission(m.copy(preparedRamp = .5f, rampBlend = .01f)))
        rejects("variants", m.copy(variants = m.variants - PopupKind.Dialog))
        rejects("variants", m.copy(variants = m.variants + (PopupKind.Legacy to PopupMotionVariant.Base)))
        rejects("variants", m.copy(variants = m.variants + (PopupKind.Menu to PopupMotionVariant.Legacy)))
        assertFalse("variants" in popupMotionAdmission(m.copy(variants = m.variants + (PopupKind.Menu to PopupMotionVariant.Auto))))
    }

    @Test fun speedIsAdmittedOnlyInItsRange() {
        for (s in (5..15).map { it / 10f }) assertEquals(s, ((uiSpec(InterfaceSettings(animationSpeedTenths = (s * 10).roundToInt())) as SpecResult.Accepted).spec).speed, 0f)
        for (s in listOf(.49f, 1.51f, 0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val result = uiSpec(InterfaceSettings(), UiSpecData(speed = s)); assertTrue("$s: $result", result is SpecResult.Rejected && "speed" in result.reasons)
        }
        assertEquals(MinimumAnimationSpeed, ((uiSpec(InterfaceSettings(), UiSpecData(speed = .5f)) as SpecResult.Accepted).spec).speed, 0f)
        assertEquals(MaximumAnimationSpeed, ((uiSpec(InterfaceSettings(), UiSpecData(speed = 1.5f)) as SpecResult.Accepted).spec).speed, 0f)
    }
}
