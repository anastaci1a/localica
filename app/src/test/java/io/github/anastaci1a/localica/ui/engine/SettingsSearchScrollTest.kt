package io.github.anastaci1a.localica.ui.engine

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import io.github.anastaci1a.localica.ui.spec.MotionAccess
import io.github.anastaci1a.localica.ui.spec.StockUiSpec
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class SettingsSearchScrollTest {
    @Test fun exactEvaluatorKeepsFirstFrameAndEveryIntermediateValueAtArbitrarySpeeds() {
        for (speed in listOf(4f / 3f, 2f, 1.1f, 1f)) for (distance in listOf(731f, -463f, 0f)) {
            EngineHarness().use { h ->
                val animation = TargetBasedAnimation(tween<Float>((350 / speed).toInt()), Float.VectorConverter, 0f, distance)
                val values = mutableListOf<Float>()
                val intent = SettingsSearchScrollIntent(h.clock.allocateId(), Job(), animation, { 1f }, values::add)
                h.clock.register(intent)
                h.advance(.016)
                assertEquals(animation.getValueFromNanos(0).toRawBits(), values.last().toRawBits())
                var played = 0L
                while (!intent.settled) {
                    h.advance(.016)
                    played += 16_000_000L
                    assertEquals(animation.getValueFromNanos(played).toRawBits(), values.last().toRawBits())
                }
                assertEquals(distance.toRawBits(), values.last().toRawBits())
                assertTrue(intent.completed.isCompleted)
                assertEquals(0, h.clock.activeCount)
            }
        }
    }
    @Test fun livePlatformScaleUsesTotalElapsedOnceAndZeroCompletesOnNextFrame() {
        for (initial in listOf(0f, .5f, 1f, 2f)) EngineHarness().use { h ->
            var scale = initial
            val animation = TargetBasedAnimation(tween<Float>(262), Float.VectorConverter, 0f, 737f)
            val values = mutableListOf<Float>()
            val intent = SettingsSearchScrollIntent(h.clock.allocateId(), Job(), animation, { scale }, values::add)
            h.clock.register(intent)
            h.advance(.016)
            assertEquals(animation.getValueFromNanos(if (initial == 0f) animation.durationNanos else 0L), values.last(), 0f)
            if (initial != 0f) {
                scale = 2f
                h.advance(.016)
                assertEquals(animation.getValueFromNanos((16_000_000L / initial).toLong()), values.last(), 0f)
                h.advance(.016)
                assertEquals(animation.getValueFromNanos((32_000_000L / 2f).toLong()), values.last(), 0f)
                scale = 0f
                h.advance(.016)
                // The pending callback uses the scale sampled before it awaited that frame.
                assertFalse(intent.settled)
                h.advance(.016)
                assertEquals(737f, values.last(), 0f)
            }
            assertTrue(intent.completed.isCompleted)
            assertEquals(0, h.clock.activeCount)
        }
    }
    @Test fun rootReducedKeepsAdmittedCurveAndCallerCancellationRetiresWithoutAnotherWrite() {
        EngineHarness().use { h ->
            val job = Job()
            val values = mutableListOf<Float>()
            val animation = TargetBasedAnimation(tween<Float>(262), Float.VectorConverter, 0f, 731f)
            val intent = SettingsSearchScrollIntent(h.clock.allocateId(), job, animation, { 1f }, values::add)
            h.clock.register(intent)
            h.advance(.016)
            h.clock.configure(EngineContext(1f / 120, 1000f, MotionAccess.Reduced), StockUiSpec)
            assertEquals(1, h.clock.activeCount)
            h.advance(.016)
            assertEquals(animation.getValueFromNanos(16_000_000), values.last(), 0f)
            val before = values.toList()
            job.cancel()
            h.advance(.016)
            assertEquals(before, values)
            assertEquals(0, h.clock.activeCount)
        }
    }
}
