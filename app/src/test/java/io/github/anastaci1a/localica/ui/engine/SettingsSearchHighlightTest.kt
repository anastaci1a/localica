package io.github.anastaci1a.localica.ui.engine

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import io.github.anastaci1a.localica.ui.spec.MotionAccess
import io.github.anastaci1a.localica.ui.spec.StockUiSpec
import org.junit.Assert.*
import org.junit.Test

class SettingsSearchHighlightTest {
    @Test fun sharedFramesOwnExactIncomingTweenAndHoldBoundaries() {
        EngineHarness().use { h ->
            val pulse = SettingsSearchHighlight(h.clock)
            val onset = TargetBasedAnimation(tween<Float>(150), Float.VectorConverter, 0f, .18f)
            val fade = TargetBasedAnimation(tween<Float>(600), Float.VectorConverter, .18f, 0f)
            pulse.start(false)
            assertEquals(1, h.clock.activeCount)
            h.advance(.016)
            assertEquals(0f, pulse.value, 0f)
            h.advance(.064)
            assertEquals(onset.getValueFromNanos(64_000_000), pulse.value, 0f)
            h.advance(.096)
            assertEquals(.18f, pulse.value, 0f)
            h.advance(.449)
            assertEquals(.18f, pulse.value, 0f)
            h.advance(.001)
            assertEquals(.18f, pulse.value, 0f)
            h.advance(.3)
            assertEquals(fade.getValueFromNanos(300_000_000), pulse.value, 0f)
            h.advance(.3)
            assertEquals(0f, pulse.value, 0f)
            assertTrue(pulse.settled)
            assertEquals(0, h.clock.activeCount)
        }
    }
    @Test fun fixedReducedKeepsItsExactHoldAndRetires() {
        EngineHarness(MotionAccess.Reduced).use { h ->
            val pulse = SettingsSearchHighlight(h.clock)
            pulse.access(true)
            pulse.start(true)
            assertEquals(.18f, pulse.value, 0f)
            h.advance(1.199)
            assertEquals(.18f, pulse.value, 0f)
            h.advance(.001)
            assertEquals(0f, pulse.value, 0f)
            assertEquals(0, h.clock.activeCount)
        }
    }
    @Test(timeout = 5000) fun globalReductionRetiresEveryPhaseAndNeverResumesStaleWork() {
        for (seconds in listOf(.064, .32, .8)) EngineHarness().use { h ->
            val pulse = SettingsSearchHighlight(h.clock)
            pulse.start(false)
            h.advance(.016)
            repeat((seconds / .016).toInt()) { h.advance(.016) }
            assertTrue(pulse.value > 0f)
            h.clock.configure(EngineContext(1f / 120, 1000f, MotionAccess.Reduced), StockUiSpec)
            assertEquals(0, h.clock.activeCount)
            assertEquals(0f, pulse.value, 0f)
            h.clock.configure(EngineContext(1f / 120, 1000f, MotionAccess.Standard), StockUiSpec)
            h.advance(2.0)
            assertEquals(0, h.clock.activeCount)
            assertEquals(0f, pulse.value, 0f)
            pulse.start(false)
            h.advance(.016)
            h.advance(.064)
            assertTrue("A fresh admission remains possible", pulse.value > 0f)
            pulse.dispose()
            assertEquals(0, h.clock.activeCount)
        }
    }
    @Test fun cancellationAndDisposalRemoveAllRegisteredWorkAndObserversCannotDriveIt() {
        for (seconds in listOf(.064, .32, .8)) EngineHarness().use { h ->
            val pulse = SettingsSearchHighlight(h.clock)
            pulse.start(false)
            h.advance(.016)
            repeat((seconds / .016).toInt()) { h.advance(.016) }
            assertThrows(IllegalStateException::class.java) { h.clock.observe { pulse.cancel() } }
            pulse.cancel()
            assertEquals(0, h.clock.activeCount)
            assertEquals(0f, pulse.value, 0f)
            pulse.start(false)
            pulse.dispose()
            assertEquals(0, h.clock.activeCount)
            assertThrows(IllegalStateException::class.java) { pulse.start(false) }
        }
    }
}
