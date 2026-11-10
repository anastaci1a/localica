package io.github.anastaci1a.localica.ui.engine

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import io.github.anastaci1a.localica.ui.spec.MotionAccess
import io.github.anastaci1a.localica.ui.spec.StockUiSpec
import org.junit.Assert.*
import org.junit.Test

class ScrollbarMotionTest {
    @Test fun fadeAndReleaseUseTheExactIncomingEvaluatorAndAdmissionFrame() {
        EngineHarness().use { h ->
            val motion = ScrollbarMotion(h.clock)
            val fade = TargetBasedAnimation(tween<Float>(220, 1200), Float.VectorConverter, 1f, 0f)
            val release = TargetBasedAnimation(tween<Float>(180), Float.VectorConverter, .3f, 0f)
            motion.reveal()
            motion.release(.3f)
            assertEquals(2, h.clock.activeCount)
            h.advance(.016)
            repeat(91) { frame ->
                val nanos = frame * 16_000_000L
                assertEquals("fade frame $frame", fade.getValueFromNanos(nanos), motion.opacity(), 0f)
                assertEquals("release frame $frame", release.getValueFromNanos(nanos), motion.releaseOffset(), 0f)
                h.advance(.016)
            }
            assertEquals(0, h.clock.activeCount)
        }
    }
    @Test fun activityReplacesOnlyTheRevealAndReleaseStartsAtTheNewGap() {
        EngineHarness().use { h ->
            val motion = ScrollbarMotion(h.clock)
            motion.reveal()
            h.advance(.016)
            h.advance(1.3)
            assertTrue(motion.opacity() in 0f..1f && motion.opacity() < 1f)
            motion.release(.2f)
            motion.reveal()
            assertEquals(1f, motion.opacity(), 0f)
            assertEquals(.2f, motion.releaseOffset(), 0f)
            assertEquals(2, h.clock.activeCount)
            h.advance(.016)
            h.advance(.08)
            val release = TargetBasedAnimation(tween<Float>(180), Float.VectorConverter, .2f, 0f)
            assertEquals(release.getValueFromNanos(80_000_000), motion.releaseOffset(), 0f)
            motion.dispose()
            assertEquals(0, h.clock.activeCount)
        }
    }
    @Test fun gripRetargetsAtThePresentedFrameAndPreservesTheIncomingCurve() {
        for (speed in listOf(1f, 4f / 3f)) EngineHarness().use { h ->
            val motion = ScrollbarMotion(h.clock)
            val easing = CubicBezierEasing(0f, 0f, .2f, 1f)
            val incoming = TargetBasedAnimation(tween<Float>((240f / speed).toInt(), easing = easing),
                Float.VectorConverter, 1f, 1.5f)
            motion.contact(true, speed)
            h.advance(.016)
            h.advance(.064)
            val value = incoming.getValueFromNanos(64_000_000)
            assertEquals(value, motion.scale(), 0f)
            val returnCurve = TargetBasedAnimation(tween<Float>((240f / speed).toInt(), easing = easing),
                Float.VectorConverter, value, 1f,
                incoming.getVelocityVectorFromNanos(64_000_000).value)
            motion.contact(false, speed)
            h.advance(.016)
            assertEquals(returnCurve.getValueFromNanos(16_000_000), motion.scale(), 0f)
            h.advance(.3)
            assertEquals(1f, motion.scale(), 0f)
            assertEquals(0, h.clock.activeCount)
        }
    }
    @Test(timeout = 5000) fun localAndGlobalReductionRetireEveryChannelWithoutReplay() {
        for (global in listOf(false, true)) for (seconds in listOf(.064, .8, 1.3)) EngineHarness().use { h ->
            val motion = ScrollbarMotion(h.clock)
            motion.reveal(); motion.release(.2f); motion.contact(true, 1f)
            h.advance(.016); h.advance(seconds)
            if (global) h.clock.configure(EngineContext(1f / 120, 1000f, MotionAccess.Reduced), StockUiSpec)
            else motion.access(true)
            assertEquals(0, h.clock.activeCount)
            assertEquals(0f, motion.opacity(), 0f)
            assertEquals(0f, motion.releaseOffset(), 0f)
            assertEquals(1.5f, motion.scale(), 0f)
            motion.access(false)
            if (global) h.clock.configure(EngineContext(1f / 120, 1000f, MotionAccess.Standard), StockUiSpec)
            h.advance(2.0)
            assertEquals(0, h.clock.activeCount)
            assertEquals(0f, motion.opacity(), 0f)
            motion.reveal()
            assertEquals(1, h.clock.activeCount)
            motion.dispose()
            assertEquals(0, h.clock.activeCount)
        }
    }
    @Test fun fixedReducedCancellationAndDisposalLeaveNoRegisteredWork() {
        EngineHarness().use { h ->
            val motion = ScrollbarMotion(h.clock, reduced = true)
            motion.reveal(); motion.release(.2f); motion.contact(true, 1f)
            assertEquals(0, h.clock.activeCount)
            assertEquals(0f, motion.opacity(), 0f)
            assertEquals(0f, motion.releaseOffset(), 0f)
            assertEquals(1.5f, motion.scale(), 0f)
            motion.access(false)
            motion.reveal(); motion.release(.2f); motion.contact(false, 1f)
            assertEquals(3, h.clock.activeCount)
            assertThrows(IllegalStateException::class.java) { h.clock.observe { motion.cancel() } }
            motion.cancel()
            assertEquals(0, h.clock.activeCount)
            motion.reveal(); motion.dispose()
            assertEquals(0, h.clock.activeCount)
            assertThrows(IllegalStateException::class.java) { motion.reveal() }
        }
    }
}
