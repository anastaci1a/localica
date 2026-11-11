package io.github.anastaci1a.localica.navigation

import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.ui.artworkSwipeStiffness
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.legacyMotionSpeed
import io.github.anastaci1a.localica.ui.motionDuration
import io.github.anastaci1a.localica.ui.spec.StockUiSpec
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** #860 S3: the non-engine inputs equal the old Standard and Faster values at s = 1.0 and s = 1.5, and move monotonically in between (section 7.3). */
class AnimationSpeedCompatibilityTest {
    private val normal = InterfaceSettings(animationSpeedTenths = 10); private val faster = InterfaceSettings(animationSpeedTenths = FasterAnimationSpeedTenths)
    private fun tenths(t: Int) = InterfaceSettings(animationSpeedTenths = t)

    /** The v0.33.21 formulas, as they were before the slider: Faster was a boolean. */
    private fun oldMotionDuration(fasterMotion: Boolean, normal: Int) = if (fasterMotion) normal / 2 else normal * 3 / 4
    private fun oldMotionSpeed(fasterMotion: Boolean) = if (fasterMotion) 2f else 4f / 3f
    private fun oldSwipeStiffness(fasterMotion: Boolean) = if (fasterMotion) 650f else 380f

    @Test fun motionDurationEqualsTheOldValuesAtOneAndOnePointFiveForEveryDuration() {
        for (ms in 0..3000) {
            assertEquals("normal $ms", oldMotionDuration(false, ms), normal.motionDuration(ms))
            assertEquals("faster $ms", oldMotionDuration(true, ms), faster.motionDuration(ms))
        }
        for (reduced in listOf(true)) for (t in 5..15) assertEquals(0, InterfaceSettings(reducedMotion = reduced, animationSpeedTenths = t).motionDuration(550))
    }

    @Test fun motionDurationIsNormalTimesThreeQuartersOverSpeedAndMonotone() {
        for (t in 5..15) for (ms in listOf(100, 220, 300, 550, 1000)) {
            val expected = ms * .75 / (t / 10.0)
            assertEquals("t=$t ms=$ms", expected, tenths(t).motionDuration(ms).toDouble(), 1.0)
            assertTrue("floored, never above the exact value", tenths(t).motionDuration(ms) <= expected + 1e-9)
        }
        for (ms in listOf(100, 550, 1000)) assertTrue((5..14).all { tenths(it).motionDuration(ms) >= tenths(it + 1).motionDuration(ms) })
        // Out-of-range stored tenths are clamped, not extrapolated.
        assertEquals(tenths(5).motionDuration(550), tenths(2).motionDuration(550)); assertEquals(tenths(15).motionDuration(550), tenths(40).motionDuration(550))
    }

    @Test fun legacyMotionSpeedIsFourThirdsTimesSpeed() {
        assertEquals(oldMotionSpeed(false), normal.legacyMotionSpeed, 0f); assertEquals(oldMotionSpeed(true), faster.legacyMotionSpeed, 0f)
        for (t in 5..15) assertEquals("t=$t", 4f / 3f * t / 10f, tenths(t).legacyMotionSpeed, 1e-6f)
        assertTrue((5..14).all { tenths(it).legacyMotionSpeed < tenths(it + 1).legacyMotionSpeed })
    }

    @Test fun artworkSwipeStiffnessEqualsTheOldValuesAndFollowsItsPowerLaw() {
        assertEquals(oldSwipeStiffness(false), normal.artworkSwipeStiffness(), 0f); assertEquals(oldSwipeStiffness(true), faster.artworkSwipeStiffness(), 0f)
        for (t in 5..15) assertEquals("t=$t", 380.0 * (650.0 / 380.0).pow(ln(t / 10.0) / ln(1.5)), tenths(t).artworkSwipeStiffness().toDouble(), 1e-3 * 380)
        assertTrue((5..14).all { tenths(it).artworkSwipeStiffness() < tenths(it + 1).artworkSwipeStiffness() })
    }

    /** The legacy input maps to the engine's time scale 1 / s for every step: the sheet law, which keeps updateSpeed, takes T / s. */
    @Test fun legacySpeedDrivesTheEngineTimeScaleToOneOverS() {
        for (t in 5..15) EngineHarness().use { h ->
            h.setContext(120)
            val owner = ExpandCollapse(h.clock, StockUiSpec.sheetExpansion, false)
            owner.updateSpeed(tenths(t).legacyMotionSpeed)
            owner.setBasis(ExpansionBasis(Rect(0f, 0f, 100f, 100f), Rect(0f, 0f, 1100f, 100f), 1f)); owner.target(true)
            val duration = StockUiSpec.sheetExpansion.referenceDurationMs / 1000.0 / (t / 10.0)
            h.advance(duration - .004); assertFalse("t=$t", owner.phase.settled)
            h.advance(.008); assertTrue("t=$t", owner.phase.settled)
            owner.dispose()
        }
    }
}
