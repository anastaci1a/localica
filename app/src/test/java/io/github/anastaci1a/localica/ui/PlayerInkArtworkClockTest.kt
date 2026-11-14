package io.github.anastaci1a.localica.ui

import androidx.compose.ui.graphics.Color
import io.github.anastaci1a.localica.ui.engine.PhaseBinding
import io.github.anastaci1a.localica.ui.engine.EngineHarness
import io.github.anastaci1a.localica.ui.engine.MotionFloat
import io.github.anastaci1a.localica.ui.engine.EngineContext
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/** #578: a song change eases the ink on the artwork blend's clock and retargets without jumps. */
class PlayerInkArtworkClockTest {
    private fun step(t: PhaseBinding, target: Color, clock: Float): Float =
        t.resolve(if (target == Color.White) 1f else 0f, clock, 1f, clock = 1).also(t::present).value

    @Test fun easesFromTheStartOfTheClockToItsEnd() {
        val t = PhaseBinding(0f)
        val values = (0..20).map { step(t, Color.White, it / 20f) }
        assertEquals(0f, values.first(), 1e-6f); assertEquals(1f, values.last(), 1e-6f)
        values.zipWithNext().forEach { (a, b) -> assertTrue(b >= a - 1e-6f && b - a <= .1f) }
    }

    @Test fun aLateDestinationEasesOverTheRemainingClockWithoutJumping() {
        val t = PhaseBinding(0f)
        (0..8).forEach { assertEquals(0f, step(t, Color.Black, it / 20f), 1e-6f) }
        var last = 0f
        (9..20).forEach { val v = step(t, Color.White, it / 20f); assertTrue("jump $last -> $v", abs(v - last) <= .2f); last = v }
        assertEquals(1f, last, 1e-6f)
    }

    @Test fun aRapidSkipRestartsTheClockAndRetargetsFromTheShownInk() {
        val t = PhaseBinding(0f)
        var last = 0f
        (0..10).forEach { last = step(t, Color.White, it / 20f) }
        assertTrue(last in .1f..0.9f)
        // A new change: the clock restarts at 0 and the destination flips back.
        (0..20).forEach { val v = step(t, Color.Black, it / 20f); assertTrue("jump $last -> $v", abs(v - last) <= .2f); last = v }
        assertEquals(0f, last, 1e-6f)
        // And the restarted clock with an unchanged destination continues from where it is, not from its old start.
        val t2 = PhaseBinding(0f)
        (0..10).forEach { last = step(t2, Color.White, it / 20f) }
        (0..8).forEach { val v = step(t2, Color.White, it / 20f); assertTrue("restart jump $last -> $v", abs(v - last) <= .2f); last = v }
    }

    @Test fun settledClockAppliesTheTargetAtOnce() {
        val t = PhaseBinding(0f)
        assertEquals(1f, step(t, Color.White, 1f), 0f)
    }

    @Test fun aDestinationAfterExpansionSettlesUsesTheSharedValueClock() {
        EngineHarness().use { h ->
            val motion = MotionFloat(h.clock, 1f, 1f, 0.0..1.0)
            val binding = PhaseBinding(1f, motion)
            fun sample(target: Float) = binding.resolve(target, 1f, 1f, clock = 2).also(binding::present).value
            assertEquals(1f, sample(1f), 0f)
            assertEquals("The arrival frame keeps the presented ink", 1f, sample(0f), 0f)
            val values = (0..40).map { h.advance(1.0 / 120); sample(0f) }
            assertTrue(values.count { it in .01f..0.99f } > 10)
            values.zipWithNext().forEach { (a, b) -> assertTrue(b <= a && a - b < .1f) }
            assertEquals(0f, values.last(), 0f)
            assertEquals("No clock remains awake", 0, h.clock.activeCount)
        }
    }

    private fun ownerStartsDuringLateEase(owner: Int) {
        EngineHarness().use { h ->
            val motion = MotionFloat(h.clock, 1f, 1f, 0.0..1.0)
            val binding = PhaseBinding(1f, motion)
            fun sample(phase: Float, end: Float, clock: Int) = binding.resolve(0f, phase, end, clock).also(binding::present).value
            sample(0f, 0f, 2)
            h.advance(.1)
            val before = motion.sample()
            assertTrue(before.value in .01f..0.99f)
            assertTrue(abs(before.velocity) > .01f)
            assertEquals(before.value, sample(0f, 1f, owner), .0001f)
            assertEquals("Owner start retains temporal velocity", before.velocity, motion.velocity(), .0001f)
            h.advance(1.0 / 120)
            val continued = motion.sample()
            assertEquals(continued.value, sample(.05f, 1f, owner), .0001f)
            assertEquals(continued.velocity, motion.velocity(), .0001f)
            (2..40).forEach { h.advance(1.0 / 120); sample((it / 20f).coerceAtMost(1f), 1f, owner) }
            assertEquals(0f, sample(1f, 1f, owner), 0f)
            assertEquals(0, h.clock.activeCount)
        }
    }

    @Test fun lyricsToggleDuringALateEaseKeepsValueAndVelocityContinuous() = ownerStartsDuringLateEase(0)
    @Test fun expansionDuringALateEaseKeepsValueAndVelocityContinuous() = ownerStartsDuringLateEase(2)

    @Test fun aNormalPhaseBoundTransitionIsUnchangedWithTheValueChannel() {
        EngineHarness().use { h ->
            val binding = PhaseBinding(0f, MotionFloat(h.clock, 0f, 1f, 0.0..1.0))
            (0..20).forEach {
                h.advance(1.0 / 120)
                val phase = it / 20f
                assertEquals(phase, binding.resolve(1f, phase, 1f, 1).also(binding::present).value, .0001f)
            }
            assertEquals(0, h.clock.activeCount)
        }
    }

    @Test fun aDestinationJustBeforeOwnerSettlementKeepsTheShownValueAndVelocity() {
        for (owner in 0..2) EngineHarness().use { h ->
            val motion = MotionFloat(h.clock, 0f, 1f, 0.0..1.0)
            val binding = PhaseBinding(0f, motion)
            fun sample(target: Float, phase: Float, clock: Int = owner) =
                binding.resolve(target, phase, 1f, clock).also(binding::present).value
            sample(1f, 0f, (owner + 1) % 3)
            h.advance(.1); sample(1f, .5f, (owner + 1) % 3)
            h.advance(.1); sample(1f, .99f)
            h.advance(.1); sample(1f, .995f)
            val before = motion.sample()
            assertTrue(before.value in .1f..0.9f)
            assertTrue(abs(before.velocity) > .01f)
            assertEquals(before.value, sample(0f, .995f), .0001f)
            assertEquals(before.velocity, motion.velocity(), .0001f)
            h.advance(1.0 / 120)
            val continued = motion.sample()
            assertEquals("Owner settlement cannot shorten the late ease", continued.value, sample(0f, 1f), .0001f)
            assertEquals(continued.velocity, motion.velocity(), .0001f)
            assertTrue(continued.value > .1f)
            var last = continued.value
            repeat(80) {
                h.advance(1.0 / 120)
                val value = sample(0f, 1f)
                assertTrue(value in 0f..1f)
                assertTrue(abs(value - last) < .2f)
                last = value
            }
            assertEquals(0f, last, 0f)
            assertEquals(0, h.clock.activeCount)
        }
    }

    @Test fun aLateDestinationAdvancesTheCurrentPhaseBeforeTakingValueOwnership() {
        for (owner in 0..2) EngineHarness().use { h ->
            val motion = MotionFloat(h.clock, 0f, 1f, 0.0..1.0)
            val binding = PhaseBinding(0f, motion)
            fun sample(target: Float, phase: Float) = binding.resolve(target, phase, 1f, owner).also(binding::present).value
            (0..9).forEach {
                if (it > 0) h.advance(1.0 / 120)
                sample(1f, it / 20f)
            }
            val previous = motion.sample()
            assertTrue(previous.velocity > .01f)
            h.advance(1.0 / 120)
            val shown = sample(0f, .5f)
            assertEquals("The phase advances on the hand-off frame", .5f, shown, .0001f)
            assertTrue(shown > previous.value)
            assertEquals("The timed correction starts at this frame's value", shown, motion.value(), .0001f)
            assertEquals(previous.velocity, motion.velocity(), .0001f)
            h.advance(1.0 / 120)
            val continued = motion.sample()
            assertTrue(continued.value > shown)
            assertEquals(continued.value, sample(0f, .55f), .0001f)
            assertEquals(continued.velocity, motion.velocity(), .0001f)
            h.advance(1.0)
            assertEquals(0f, sample(0f, 1f), 0f)
            assertEquals(0, h.clock.activeCount)
        }
    }

    @Test fun aTrackChangeDuringALateEaseRetargetsTheSameValueAndVelocity() {
        EngineHarness().use { h ->
            val motion = MotionFloat(h.clock, 1f, 1f, 0.0..1.0)
            val binding = PhaseBinding(1f, motion)
            fun sample(target: Float, phase: Float = 1f, clock: Int = 2) = binding.resolve(target, phase, 1f, clock).also(binding::present)
            sample(0f); h.advance(.1)
            val before = motion.sample()
            assertEquals(before.value, sample(1f, 0f, 1).value, 0f)
            assertEquals(before.velocity, motion.velocity(), .0001f)
            h.advance(1.0)
            assertEquals(1f, sample(1f).value, 0f)
            assertEquals(0, h.clock.activeCount)
        }
    }

    @Test fun reducedMotionAndZeroDurationScaleCompleteALateDestination() {
        for (reduced in listOf(false, true)) EngineHarness().use { h ->
            h.clock.configure(EngineContext(1f / 120, 1000f,
                if (reduced) MotionAccess.Reduced else MotionAccess.Standard, if (reduced) 1f else 0f), StockUiSpec)
            val binding = PhaseBinding(1f, MotionFloat(h.clock, 1f, 1f, 0.0..1.0))
            fun sample() = binding.resolve(0f, 1f, 1f, 2).also(binding::present).value
            sample(); h.advance(1.0 / 120)
            assertEquals(0f, sample(), 0f)
            assertEquals(0, h.clock.activeCount)
        }
    }

    @Test fun disposingDuringALateEaseUnregistersTheValueChannel() {
        EngineHarness().use { h ->
            val motion = MotionFloat(h.clock, 1f, 1f, 0.0..1.0)
            val binding = PhaseBinding(1f, motion)
            binding.present(binding.resolve(0f, 1f, 1f, 2))
            h.advance(.1)
            assertTrue(motion.value() in .01f..0.99f)
            assertEquals(1, h.clock.activeCount)
            motion.dispose()
            assertEquals(0, h.clock.activeCount)
            val awaits = h.awaits
            h.advance(1.0)
            assertEquals(awaits, h.awaits)
        }
    }
}
