package io.github.anastaci1a.localica.ui.engine

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test

class PlatformDurationScaleTest {
    private fun EngineHarness.scale(value: Float, access: MotionAccess = MotionAccess.Standard) {
        clock.configure(EngineContext(1f / 120, 1000f, access, value), StockUiSpec)
    }

    @Test fun scrollbarMatchesNativeTweenPlayTimeAtEveryFixedScaleIncludingAdmissionAndDelay() {
        for (scale in listOf(0f, .5f, 1f, 2f)) EngineHarness().use { h ->
            h.scale(scale)
            val owner = ScrollbarMotion(h.clock)
            val fade = TargetBasedAnimation(tween<Float>(220, 1200), Float.VectorConverter, 1f, 0f)
            val release = TargetBasedAnimation(tween<Float>(180), Float.VectorConverter, .3f, 0f)
            owner.reveal(); owner.release(.3f)
            h.advance(.016)
            repeat(181) { frame ->
                val realNanos = frame * 16_000_000L
                val played = if (scale == 0f) Long.MAX_VALUE else (realNanos / scale).toLong()
                assertEquals("fade scale $scale frame $frame", fade.getValueFromNanos(played), owner.opacity(), 0f)
                assertEquals("release scale $scale frame $frame", release.getValueFromNanos(played), owner.releaseOffset(), 0f)
                if (scale > 0f && frame == 1) assertTrue("A revealed scrollbar has positive visibility", owner.opacity() > 0f)
                h.advance(.016)
            }
            assertEquals(0, h.clock.activeCount)
            owner.dispose()
        }
    }

    @Test fun liveScaleUsesTotalElapsedAndCanMoveBackwardLikeAnimatable() {
        EngineHarness().use { h ->
            val owner = ScrollbarMotion(h.clock)
            val reference = TargetBasedAnimation(tween<Float>(180), Float.VectorConverter, .3f, 0f)
            owner.release(.3f)
            h.advance(.016); h.advance(.064)
            assertEquals(reference.getValueFromNanos(64_000_000), owner.releaseOffset(), 0f)
            h.scale(2f); h.advance(.016)
            assertEquals(reference.getValueFromNanos(40_000_000), owner.releaseOffset(), 0f)
            h.scale(.5f); h.advance(.016)
            assertEquals(reference.getValueFromNanos(192_000_000), owner.releaseOffset(), 0f)
            assertEquals(0, h.clock.activeCount)
            owner.dispose()
        }
    }

    @Test fun scalarSpringAndBoundExpansionUseScaledTimeWithExactScaleOneSamples() {
        for (scale in listOf(.5f, 1f, 2f)) EngineHarness().use { actual -> EngineHarness().use { control ->
            actual.scale(scale)
            val a = MotionFloat(actual.clock, 0f, 1f)
            val b = MotionFloat(control.clock, 0f, 1f)
            val springA = MotionFloat(actual.clock, 0f, 1f)
            val springB = MotionFloat(control.clock, 0f, 1f)
            val basis = ExpansionBasis(Rect(0f, 0f, 100f, 100f), Rect(0f, 0f, 1100f, 100f), 1f)
            val expansionA = ExpandCollapse(actual.clock, StockUiSpec.expansion, false)
            val expansionB = ExpandCollapse(control.clock, StockUiSpec.expansion, false)
            expansionA.setBasis(basis); expansionB.setBasis(basis)
            val rectA = MotionRect(actual.clock, basis.startBounds, EdgeRanges(1200f, 1200f, 1200f, 1200f))
            val rectB = MotionRect(control.clock, basis.startBounds, EdgeRanges(1200f, 1200f, 1200f, 1200f))
            expansionA.bind(rectA); expansionB.bind(rectB)
            a.retarget(1f, motionProfileId(MotionProfile.Control)); b.retarget(1f, motionProfileId(MotionProfile.Control))
            springA.retargetSpring(1f, springProfileId(SpringProfile.Strain))
            springB.retargetSpring(1f, springProfileId(SpringProfile.Strain))
            expansionA.target(true); expansionB.target(true)
            repeat(31) { frame ->
                val stamp = actual.clock.stampNow
                val referenceStamp = FrameStamp(1_000_000_000L + (frame * 8_000_000L / scale).toLong(), stamp.sequence)
                assertEquals(b.valueAt(referenceStamp).toRawBits(), a.valueAt(stamp).toRawBits())
                assertEquals(springB.valueAt(referenceStamp).toRawBits(), springA.valueAt(stamp).toRawBits())
                assertEquals(expansionB.phase.valueAt(referenceStamp).toRawBits(), expansionA.phase.valueAt(stamp).toRawBits())
                assertEquals(rectB.valueAt(referenceStamp), rectA.valueAt(stamp))
                actual.advance(.008)
            }
            actual.advance(10.0)
            assertTrue(a.settled); assertTrue(springA.settled); assertTrue(expansionA.phase.settled)
            assertEquals(basis.endBounds, rectA.value())
            a.dispose(); b.dispose(); springA.dispose(); springB.dispose(); expansionA.dispose(); expansionB.dispose()
        } }
    }

    @Test fun systemZeroFinishesTimedWorkButPreservesRealDrivenInputAndAppAccess() {
        EngineHarness().use { h ->
            val timed = MotionFloat(h.clock, 0f, 1f)
            val driven = MotionFloat(h.clock, 0f, 1f)
            timed.retarget(1f, motionProfileId(MotionProfile.Control))
            driven.drive(.2f, h.clock.stampNow)
            h.now += 10_000_000
            driven.drive(.4f, h.clock.stampNow)
            val before = driven.sampleAt(h.clock.stampNow)
            val scrollbar = ScrollbarMotion(h.clock)
            scrollbar.reveal(); scrollbar.release(.3f)
            h.advance(.016)
            h.scale(0f)
            h.advance(.016)
            assertEquals(MotionAccess.Standard, h.clock.context!!.access)
            assertTrue(timed.settled); assertEquals(1f, timed.value(), 0f)
            assertFalse(driven.settled); assertEquals(before, driven.sampleAt(h.clock.stampNow))
            assertEquals(0f, scrollbar.opacity(), 0f); assertEquals(0f, scrollbar.releaseOffset(), 0f)
            assertEquals(1, h.clock.activeCount)
            driven.release(1f, motionProfileId(MotionProfile.Control), before.velocity)
            h.advance(.016)
            assertTrue(driven.settled); assertEquals(1f, driven.value(), 0f)
            timed.dispose(); driven.dispose(); scrollbar.dispose()
            assertEquals(0, h.clock.activeCount)
        }
    }

    @Test fun scaledRetargetAndReleaseKeepInheritedPlayTimeVelocityUnits() {
        for (scale in listOf(.5f, 1f, 2f)) EngineHarness().use { h ->
            h.scale(scale)
            val motion = MotionFloat(h.clock, 0f, 1f)
            val profile = motionProfileId(MotionProfile.Control)
            motion.retarget(1f, profile)
            h.advance(.032)
            val stamp = h.clock.stampNow
            val before = motion.sampleAt(stamp)
            val x = motion.rawValue(stamp)
            val velocity = motion.rawVelocity(stamp)
            motion.retarget(.4f, profile)
            assertEquals("Retarget preserves evaluator velocity without a wall-time conversion", before, motion.sampleAt(stamp))
            val data = StockUiSpec.motion.getValue(MotionProfile.Control) as MotionProfileSpec
            val reference = Eased(x, velocity, .4f.toDouble(), data.easing, data.durationMs / 1000.0)
            h.advance(.016)
            val played = .016 / scale
            assertEquals(reference.value(played).toFloat(), motion.valueAt(h.clock.stampNow), 0f)
            assertEquals(reference.velocity(played).toFloat(), motion.velocityAt(h.clock.stampNow), 0f)
            motion.drive(.2f, h.clock.stampNow)
            h.now += 10_000_000
            motion.drive(.3f, h.clock.stampNow)
            val inputVelocity = motion.velocityAt(h.clock.stampNow)
            motion.release(1f, profile, inputVelocity)
            assertEquals(inputVelocity, motion.velocityAt(h.clock.stampNow), 0f)
            motion.dispose()
        }
    }

    @Test fun platformScaleValidationRejectsInvalidInputs() {
        for (scale in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { EngineContext(1f / 120, 1000f, MotionAccess.Standard, scale) }
        }
    }
}
