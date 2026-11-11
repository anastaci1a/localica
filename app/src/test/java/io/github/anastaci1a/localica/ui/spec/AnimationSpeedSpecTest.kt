package io.github.anastaci1a.localica.ui.spec

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.effectiveReducedMotion
import io.github.anastaci1a.localica.ui.engine.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * #860 and #862 S1. The engine context of the app is speed = settings.animationSpeed and durationScale = respect ? platform scale : 1
 * (LocalicaUiRoot); these tests drive the engine with exactly that context and check the popup spring, the sheet, lyrics and selection-panel laws
 * and Reduced priority. The selection panel is not a popup: it keeps #869's tween and takes the slider and the respected scale like the drawers.
 */
class AnimationSpeedSpecTest {
    private val scales = listOf(.5f, 1f)
    private val travel = ExpansionBasis(Rect(0f, 0f, 100f, 100f), Rect(0f, 0f, 1100f, 100f), 1f)

    @Test fun settingsCarryTheSpeedAndRepairOutOfRangeTenths() {
        for (tenths in 5..15) assertEquals(tenths / 10f, InterfaceSettings(animationSpeedTenths = tenths).animationSpeed, 0f)
        assertEquals(.5f, InterfaceSettings(animationSpeedTenths = 3).animationSpeed, 0f); assertEquals(1.5f, InterfaceSettings(animationSpeedTenths = 99).animationSpeed, 0f)
        assertEquals(1f, InterfaceSettings().animationSpeed, 0f); assertTrue(InterfaceSettings().respectSystemAnimationScale)
        for (tenths in listOf(-3, 0, 4, 16, 99)) {
            val spec = (uiSpec(InterfaceSettings(animationSpeedTenths = tenths)) as SpecResult.Accepted).spec
            assertEquals("$tenths", InterfaceSettings(animationSpeedTenths = tenths).animationSpeed, spec.speed, 0f)
        }
    }

    @Test fun popupSpringRunsAtOmegaTimesSpeedAndItsTimesStretchByTheRespectedScale() {
        val open = StockPopupMotion.open; val z = open.zeta.toDouble()
        val base = SpringOracle.crossing(.5, open.omega.toDouble(), z)
        for (tenths in 5..15) for (platform in scales) for (respect in listOf(true, false)) EngineHarness().use { h ->
            val s = tenths / 10f; val clock = if (respect) platform else 1f
            h.setContext(120, s, clock)
            val rig = PopupRig(h, travel, PopupKind.Menu, bound = false); rig.owner.target(true); val start = h.clock.stampNow
            // t50 by bisection on the engine's own phase (monotone before its first peak).
            var lo = 0.0; var hi = .5; repeat(60) { val mid = (lo + hi) / 2; if (rig.owner.main.rawValue(stampAt(start, mid)) < .5) lo = mid else hi = mid }
            val label = "s=$s platform=$platform respect=$respect"
            assertEquals("$label t50 = t50(1) x ${if (respect) "scale" else "1"} / s", base * clock / s, hi, 5e-9)
            // The same shape: the phase at scaled time equals the unscaled phase.
            for (k in 1..20) { val t = k * .01; val expected = SpringOracle.fromRest(open.omega.toDouble(), z, t * s / clock)
                // Inside the settle envelope the engine joins rest through its terminal bridge, within the settle threshold.
                assertEquals("$label shape at $t", expected, rig.owner.main.rawValue(stampAt(start, t)), if (abs(expected - 1) < 1e-3) 1e-3 else 1e-9) }
            rig.owner.dispose()
        }
    }

    /** The settled time of a law from rest at travel = reference: sigma times its reference duration (floored by two frames), then stretched by the clock factor. */
    @Test fun sheetLyricsAndSelectionLawsTakeTOverSpeedStretchedByTheClock() {
        for (tenths in 5..15) for (platform in scales) for (respect in listOf(true, false)) for ((name, law) in listOf("sheet" to StockUiSpec.sheetExpansion, "lyrics" to StockUiSpec.lyricsExpansion, "selection" to StockUiSpec.selectionExpansion)) EngineHarness().use { h ->
            val s = tenths / 10f; val clock = if (respect) platform else 1f
            h.setContext(120, s, clock)
            val owner = ExpandCollapse(h.clock, law, false); owner.setBasis(travel); owner.target(true)
            val duration = max((1.0 / s) * max(law.floorMs, law.referenceDurationMs) / 1000.0, 2.0 * (1f / 120).toDouble())
            assertEquals("$name s=$s", duration, expansionDuration(law, 1000.0, 1000.0, 0.0, 0.0, 1.0, 1.0 / s, (1f / 120).toDouble()), 1e-9)
            val label = "$name s=$s platform=$platform respect=$respect"
            h.advance(duration * clock - .004); assertFalse("$label still moving", owner.phase.settled)
            h.advance(.008); assertTrue("$label settled", owner.phase.settled)
            owner.dispose()
        }
    }

    /** #869's selection tween on the slider: 165 ms at 1x (v0.33.21's default speed 4/3), 110 ms at 1.5x (the old Faster's speed 2), and
     * 330 ms at .5x; never the popup spring. */
    @Test fun selectionPanelTweenMapsTheOldFasterToTheSlider() {
        val law = StockUiSpec.selectionExpansion
        assertNotEquals(StockUiSpec.expansion, law)
        for ((s, ms) in listOf(1f to 165.0, 1.5f to 110.0, .5f to 330.0))
            assertEquals("s=$s", ms / 1000, expansionDuration(law, 1000.0, 1000.0, 0.0, 0.0, 1.0, 1.0 / s, (1f / 120).toDouble()), 1e-9)
    }

    @Test fun reducedHasPriorityOverSpeedAndTheRespectedScale() {
        // effectiveReducedMotion(app, platform scale, respect): the app setting, or a respected scale of 0.
        for (app in listOf(false, true)) for (scale in listOf(0f, .5f, 1f, 2f)) for (respect in listOf(true, false))
            assertEquals("app=$app scale=$scale respect=$respect", app || respect && scale == 0f, effectiveReducedMotion(app, scale, respect))
        assertFalse("scale 0 with respect off is not Reduced", effectiveReducedMotion(false, 0f, false))
        assertTrue("scale 0 with respect on is Reduced", effectiveReducedMotion(false, 0f, true))
        assertTrue("the default respects the scale", effectiveReducedMotion(false, 0f))
        // The root folds a respected scale of 0 into the access of the spec; the app setting always does.
        val domain = ExpansionGeometryDomain(1000f, 4000f, 1f / 30)
        for (reduced in listOf(false, true)) for (systemReduced in listOf(false, true)) for (tenths in listOf(5, 10, 15))
            assertEquals("app=$reduced system=$systemReduced", if (reduced || systemReduced) MotionAccess.Reduced else MotionAccess.Standard,
                uiSpec(InterfaceSettings(reducedMotion = reduced, animationSpeedTenths = tenths), domain, Density(1f), systemReduced).spec.access)
        // Reduced rests at once whatever the speed: a popup spring and the sheet, lyrics and selection laws all land on their target on the intent frame.
        for (tenths in listOf(5, 10, 15)) EngineHarness(access = MotionAccess.Reduced).use { h ->
            h.setContext(120, tenths / 10f, 1f, MotionAccess.Reduced)
            val popup = PopupRig(h, travel, PopupKind.Menu, bound = false); popup.owner.target(true)
            assertTrue(popup.owner.main.settled); assertEquals(1.0, popup.owner.main.rawValue(h.clock.stampNow), 0.0)
            for (law in listOf(StockUiSpec.sheetExpansion, StockUiSpec.lyricsExpansion, StockUiSpec.selectionExpansion)) {
                val owner = ExpandCollapse(h.clock, law, false); owner.setBasis(travel); owner.target(true)
                assertTrue(owner.phase.settled); assertEquals(1.0, (owner.phase as MotionFloat).rawValue(h.clock.stampNow), 0.0); owner.dispose()
            }
            popup.owner.dispose()
        }
    }
}
