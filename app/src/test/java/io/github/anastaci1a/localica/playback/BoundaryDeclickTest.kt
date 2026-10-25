package io.github.anastaci1a.localica.playback

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class BoundaryDeclickTest {
    private val mono = BoundaryDeclick.Pcm16Format(48_000, 1)
    private fun constant(value: Int, frames: Int = 160) = ShortArray(frames) { value.toShort() }
    private fun plan(tail: ShortArray, head: ShortArray, format: BoundaryDeclick.Pcm16Format = mono) =
        BoundaryDeclick.plan(tail, head, format, format, enabled = true)

    private fun unchanged(result: BoundaryDeclick.Plan, original: ShortArray) {
        assertFalse(result.applied)
        assertEquals(0, result.replacedFrames)
        assertEquals(0, result.peakCorrection)
        assertEquals(original.size, result.inputSamples)
        assertEquals(original.size, result.outputSamples)
        assertArrayEquals(original, result.output)
        assertNotSame(original, result.output)
    }

    private fun bounded(result: BoundaryDeclick.Plan, tail: ShortArray, head: ShortArray, channels: Int) {
        assertTrue(result.applied)
        assertEquals(BoundaryDeclick.Reason.REPAIRED, result.reason)
        assertEquals(head.size, result.output.size)
        assertEquals(head.size, result.inputSamples)
        assertEquals(head.size, result.outputSamples)
        assertEquals(head.size / channels, result.inputFrames)
        assertEquals(result.inputFrames, result.outputFrames)
        assertEquals(result.windowFrames, result.replacedFrames)
        assertTrue(result.peakCorrection in 1..512)
        assertTrue(result.plannedBoundaryStep < result.originalBoundaryStep)
        assertTrue(result.plannedPeakAdjacentStep <= result.originalPeakAdjacentStep)
        for (channel in 0 until channels) {
            val left = tail[tail.size - channels + channel].toInt()
            val right = head[result.windowFrames * channels + channel].toInt()
            for (frame in 0 until result.windowFrames) {
                val sample = result.output[frame * channels + channel].toInt()
                assertTrue("No endpoint overshoot", sample in minOf(left, right)..maxOf(left, right))
            }
        }
        for (i in result.windowFrames * channels until head.size) assertEquals(head[i], result.output[i])
    }

    @Test fun defaultOffPreservesExactBytesAndOwnsItsOutputEvenForUnsupportedGeometry() {
        val tail = constant(1000)
        val head = constant(1200)
        val originalTail = tail.copyOf()
        val result = BoundaryDeclick.plan(tail, head, mono, mono)
        assertEquals(BoundaryDeclick.Reason.DISABLED, result.reason)
        unchanged(result, head)
        result.output[0] = 7
        assertEquals(1200.toShort(), head[0])
        assertArrayEquals(originalTail, tail)
        unchanged(BoundaryDeclick.plan(tail, head, mono, BoundaryDeclick.Pcm16Format(0, 0)), head)
    }

    @Test fun continuousRampFlatAndSilenceAreExactPassthrough() {
        val tail = ShortArray(16) { (1000 + it).toShort() }
        val head = ShortArray(160) { (1016 + it).toShort() }
        val cases = listOf(tail to head, constant(1000) to constant(1000), constant(0) to constant(0))
        for ((left, right) in cases) {
            val result = plan(left, right)
            assertEquals(BoundaryDeclick.Reason.CONTINUOUS, result.reason)
            unchanged(result, right)
        }
    }

    @Test fun quietLegitimateOnsetsAndHardTransientsAreNotRepaired() {
        for ((left, right) in listOf(0 to 1000, 16 to 96, 1000 to 8000)) {
            val head = constant(right)
            unchanged(plan(constant(left), head), head)
        }
        val transient = constant(1200).also { it[3] = 8000 }
        unchanged(plan(constant(1000), transient), transient)
    }

    @Test fun isolatedSmallDiscontinuityReplacesOnlyHeadAndMatchesDiscreteEndpointSlopes() {
        val tail = constant(1000)
        val head = constant(1200)
        val beforeTail = tail.copyOf()
        val beforeHead = head.copyOf()
        val result = plan(tail, head)
        bounded(result, tail, head, 1)
        assertEquals(24, result.windowFrames)
        assertTrue(abs(result.output[0].toInt() - 1000) <= 3)
        assertTrue(abs(1200 - result.output[23].toInt()) <= 3)
        for (i in 1..24) assertTrue(result.output[i] >= result.output[i - 1])
        assertArrayEquals(beforeTail, tail)
        assertArrayEquals(beforeHead, head)
        val slopedTail = ShortArray(16) { (1000 + it).toShort() }
        val slopedHead = ShortArray(160) { (1216 + it).toShort() }
        val sloped = plan(slopedTail, slopedHead)
        bounded(sloped, slopedTail, slopedHead, 1)
        val firstStep = sloped.output[0].toInt() - slopedTail.last().toInt()
        val finalStep = slopedHead[sloped.windowFrames].toInt() - sloped.output[sloped.windowFrames - 1].toInt()
        assertTrue(abs(firstStep - 1) <= 4)
        assertTrue(abs(finalStep - 1) <= 4)
    }

    @Test fun stereoUsesOneAlignedWindowAndRetainsBothIndependentChannelEndpoints() {
        val stereo = BoundaryDeclick.Pcm16Format(48_000, 2)
        val tail = ShortArray(32) { if (it % 2 == 0) 1000 else -2000 }
        val head = ShortArray(320) { if (it % 2 == 0) 1200 else -2200 }
        val savedTail = tail.copyOf()
        val savedHead = head.copyOf()
        val result = plan(tail, head, stereo)
        bounded(result, tail, head, 2)
        assertTrue(result.output[0] in 1000..1200)
        assertTrue(result.output[1] in -2200..-2000)
        assertArrayEquals(savedTail, tail)
        assertArrayEquals(savedHead, head)
    }

    @Test fun oneAmbiguousChannelRejectsWholePlanWithoutPartialRepairs() {
        val stereo = BoundaryDeclick.Pcm16Format(48_000, 2)
        val tail = ShortArray(32) { if (it % 2 == 0) 1000 else 0 }
        val head = ShortArray(320) { if (it % 2 == 0) 1200 else 1000 }
        val result = plan(tail, head, stereo)
        assertEquals(BoundaryDeclick.Reason.QUIET_OR_ONSET, result.reason)
        unchanged(result, head)
    }

    @Test fun replacementDurationTracksRateAndRemainsBelowOneMillisecond() {
        for (rate in listOf(8000, 16000, 44100, 48000, 96000, 192000)) {
            val format = BoundaryDeclick.Pcm16Format(rate, 1)
            val tail = constant(1000)
            val head = constant(1200)
            val result = plan(tail, head, format)
            bounded(result, tail, head, 1)
            assertEquals(rate / 2000, result.windowFrames)
            assertTrue(result.windowFrames * 1_000_000L / rate <= 500)
        }
    }

    @Test fun unsupportedMismatchedIncompleteAndInsufficientContextsFailClosed() {
        val tail = constant(1000)
        val head = constant(1200)
        for (format in listOf(BoundaryDeclick.Pcm16Format(7999, 1), BoundaryDeclick.Pcm16Format(192001, 1),
            BoundaryDeclick.Pcm16Format(48000, 0), BoundaryDeclick.Pcm16Format(48000, 9))) {
            val result = BoundaryDeclick.plan(tail, head, format, format, true)
            assertEquals(BoundaryDeclick.Reason.UNSUPPORTED_FORMAT, result.reason)
            unchanged(result, head)
        }
        for (old in listOf(BoundaryDeclick.Pcm16Format(44100, 1), BoundaryDeclick.Pcm16Format(48000, 2))) {
            val result = BoundaryDeclick.plan(tail, head, old, mono, true)
            assertEquals(BoundaryDeclick.Reason.INCOMPATIBLE_FORMAT, result.reason)
            unchanged(result, head)
        }
        val stereo = BoundaryDeclick.Pcm16Format(48000, 2)
        for ((left, right) in listOf(ShortArray(15) to ShortArray(100), ShortArray(16) to ShortArray(101))) {
            val result = plan(left, right, stereo)
            assertEquals(BoundaryDeclick.Reason.INCOMPLETE_FRAMES, result.reason)
            unchanged(result, right)
        }
        for ((left, right) in listOf(ShortArray(7) to head, tail to ShortArray(32), ShortArray(0) to head)) {
            val result = plan(left, right)
            assertEquals(BoundaryDeclick.Reason.INSUFFICIENT_CONTEXT, result.reason)
            unchanged(result, right)
        }
    }

    @Test fun nearClippingHighSlopeAndOpposingEndpointSlopeAreRejected() {
        val clippedHead = constant(32700)
        assertEquals(BoundaryDeclick.Reason.NEAR_CLIPPING, plan(constant(32600), clippedHead).reason)
        unchanged(plan(constant(32600), clippedHead), clippedHead)
        val fastTail = ShortArray(16) { (1000 + 30 * it).toShort() }
        val fastHead = ShortArray(160) { (1650 + 30 * it).toShort() }
        val fast = plan(fastTail, fastHead)
        assertEquals(BoundaryDeclick.Reason.TRANSIENT_OR_HIGH_SLOPE, fast.reason)
        unchanged(fast, fastHead)
        val reversingTail = ShortArray(16) { (1015 - it).toShort() }
        val head = constant(1200)
        val reversing = plan(reversingTail, head)
        assertEquals(BoundaryDeclick.Reason.REVERSAL, reversing.reason)
        unchanged(reversing, head)
    }

    @Test fun relativeNeighborVariationAndCorrectionMagnitudeAreConservative() {
        val wavering = constant(1200).also { it[2] = 1206 }
        val transient = plan(constant(1000), wavering)
        assertEquals(BoundaryDeclick.Reason.TRANSIENT_OR_HIGH_SLOPE, transient.reason)
        unchanged(transient, wavering)
        val tooLarge = constant(1700)
        val result = plan(constant(1000), tooLarge)
        assertEquals(BoundaryDeclick.Reason.AMBIGUOUS_BOUNDARY, result.reason)
        unchanged(result, tooLarge)
        val excessiveCorrection = constant(400)
        val correction = plan(constant(200), excessiveCorrection)
        assertEquals(BoundaryDeclick.Reason.CORRECTION_TOO_LARGE, correction.reason)
        unchanged(correction, excessiveCorrection)
    }

    @Test fun smallSignedJumpsRemainBoundedWithoutOvershootOrFrameCountChanges() {
        for (level in listOf(-4000, -1000, 1000, 4000)) for (jump in listOf(-256, -64, 64, 256)) {
            val tail = constant(level)
            val head = constant(level + jump)
            val result = plan(tail, head)
            bounded(result, tail, head, 1)
            assertArrayEquals(constant(level), tail)
            assertArrayEquals(constant(level + jump), head)
        }
    }
}
