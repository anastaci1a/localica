package io.github.anastaci1a.localica.ui.engine

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp

class PreviewContactTest {
    @Test fun relocatedSpringAndPressureKeepTheOriginalFloatArithmetic() {
        for (speed in listOf(.5f, 1f, 4f/3f, 2f)) {
            val before = TargetBasedAnimation(spring<Float>(1f, 90f * speed * speed, visibilityThreshold = .0001f), Float.VectorConverter, 85f, 0f)
            val after = TargetBasedAnimation(glassMorphAnimationSpec(speed), Float.VectorConverter, 85f, 0f)
            assertEquals(before.durationNanos, after.durationNanos)
            for (millis in 0..2000) assertEquals(before.getValueFromNanos(millis * 1_000_000L).toRawBits(), after.getValueFromNanos(millis * 1_000_000L).toRawBits())
        }
        for (excess in listOf(-10f, -1f, -.01f, -0f, 0f, .01f, 1f, 10f))
            assertEquals((-.22f * (1f - exp(-abs(excess) * 2.4f))).toRawBits(), glassContactPressure(excess).toRawBits())
    }
    @Test fun contactRetargetAndReducedReleaseKeepOneCancellableRootLifetime() {
        EngineHarness().use { h ->
            val owner = BrowsePinchProgressOwner().also { it.bind(h.clock, Dispatchers.Unconfined) }
            val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            var reduced = false
            val contact = PreviewContact(owner, scope, { 100f }, { reduced })
            contact.contact(true); contact.start(); contact.dragBy(150f)
            assertEquals(100f, contact.travel, 0f)
            assertEquals(glassContactPressure(.5f).toRawBits(), contact.pressure.toRawBits())
            contact.release(); assertEquals(1, h.clock.activeCount)
            h.advance(.01); h.advance(.04)
            val interrupted = contact.travel
            contact.start(); assertEquals(0, h.clock.activeCount)
            contact.dragBy(-5f); assertEquals(interrupted - 5f, contact.travel, 0f)
            reduced = true; contact.contact(false); contact.release()
            assertFalse(contact.pressed); assertEquals(0f, contact.travel, 0f)
            assertEquals(0, h.clock.activeCount); scope.cancel()
        }
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun dragDeltasKeepTheExistingOwnerDispatchBoundary() {
        EngineHarness().use { h ->
            val owner = BrowsePinchProgressOwner().also { it.bind(h.clock, Dispatchers.Unconfined) }
            val queued = kotlinx.coroutines.test.StandardTestDispatcher()
            val scope = CoroutineScope(queued + SupervisorJob())
            var boundary = 100f
            var reduced = false
            val contact = PreviewContact(owner, scope, { boundary }, { reduced })
            try {
                contact.start(); contact.dragBy(10f)
                assertEquals("The original preview publishes travel from its owner job", 0f, contact.travel, 0f)
                queued.scheduler.runCurrent()
                assertEquals(10f, contact.travel, 0f)
                contact.dragBy(20f); contact.dragBy(30f)
                assertEquals(10f, contact.travel, 0f)
                queued.scheduler.runCurrent()
                assertEquals(60f, contact.travel, 0f)
                contact.start(); contact.dragBy(150f)
                boundary = 200f
                queued.scheduler.runCurrent()
                assertEquals("Queued travel keeps the geometry sampled by its input event", 100f, contact.travel, 0f)
                contact.release()
                reduced = true
                queued.scheduler.runCurrent()
                assertEquals("Release keeps the reduction policy sampled by its input event", 1, h.clock.activeCount)
                assertEquals(100f, contact.travel, 0f)
            } finally {
                scope.cancel(); queued.scheduler.runCurrent()
                assertEquals(0, h.clock.activeCount)
            }
        }
    }

}
