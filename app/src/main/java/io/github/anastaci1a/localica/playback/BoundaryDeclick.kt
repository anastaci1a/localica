package io.github.anastaci1a.localica.playback

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Optional PCM16 boundary policy, not an acoustic classifier or a playback processor.
 * The caller owns format/track identity and supplies already-submitted tail plus untouched head.
 * Only a prefix of head may be replaced; no previous sample, frame count or ordering changes.
 * No buffering, interpolation across overlapping tracks, silence trimming or file writes occur here.
 *
 * Conservative engineering thresholds (not audibility claims): 8 stable context frames;
 * <=0.5 ms replacement (floor(rate/2000) frames); 8..192 kHz, 1..8 channels;
 * <=75% full-scale context; >=128-unit activity on BOTH sides with <=2:1 peak ratio;
 * isolated 64..512-unit jump >=8x neighboring steps; neighboring slope <=24 units
 * per 48 kHz sample, with slope variation <=one quarter of that limit (minimum2).
 * Correction <=512 units, <=half local activity and <=original jump. Ambiguity in ANY
 * channel rejects the entire plan. These deliberately miss many possible clicks.
 */
internal object BoundaryDeclick {
    data class Pcm16Format(val sampleRate: Int, val channels: Int)

    enum class Reason {
        DISABLED, UNSUPPORTED_FORMAT, INCOMPATIBLE_FORMAT, INCOMPLETE_FRAMES,
        INSUFFICIENT_CONTEXT, CONTINUOUS, QUIET_OR_ONSET, NEAR_CLIPPING,
        TRANSIENT_OR_HIGH_SLOPE, AMBIGUOUS_BOUNDARY, REVERSAL,
        CORRECTION_TOO_LARGE, CANDIDATE_OVERSHOOT, CANDIDATE_STEP,
        CANDIDATE_SLOPE, REPAIRED,
    }

    data class Plan(
        val reason: Reason,
        /** Owned copy, including unchanged decisions. Neither input array is modified or retained. */
        val output: ShortArray,
        val inputSamples: Int,
        val outputSamples: Int,
        val inputFrames: Int?,
        val outputFrames: Int?,
        val windowFrames: Int,
        val replacedFrames: Int,
        val peakCorrection: Int,
        /** Step metrics are zero when insufficient/unsupported context prevents inspection. */
        val originalBoundaryStep: Int,
        val plannedBoundaryStep: Int,
        val originalPeakAdjacentStep: Int,
        val plannedPeakAdjacentStep: Int,
    ) {
        val applied: Boolean get() = reason == Reason.REPAIRED
    }

    fun plan(
        previousTail: ShortArray,
        head: ShortArray,
        previousFormat: Pcm16Format,
        currentFormat: Pcm16Format,
        enabled: Boolean = false,
    ): Plan {
        val output = head.copyOf()
        val channels = currentFormat.channels
        val frames = if (channels > 0 && head.size % channels == 0) head.size / channels else null
        var window = 0
        var originalBoundary = 0
        var originalPeak = 0
        fun unchanged(reason: Reason) = Plan(reason, head.copyOf(), head.size, head.size,
            frames, frames, window, 0, 0, originalBoundary, originalBoundary, originalPeak, originalPeak)
        if (!enabled) return unchanged(Reason.DISABLED)
        if (currentFormat.sampleRate !in 8_000..192_000 || channels !in 1..8) {
            return unchanged(Reason.UNSUPPORTED_FORMAT)
        }
        if (previousFormat != currentFormat) return unchanged(Reason.INCOMPATIBLE_FORMAT)
        if (frames == null || previousTail.size % channels != 0) return unchanged(Reason.INCOMPLETE_FRAMES)
        window = currentFormat.sampleRate / 2_000
        val tailFrames = previousTail.size / channels
        val context = 8
        if (tailFrames < context || frames < window + context + 1) {
            return unchanged(Reason.INSUFFICIENT_CONTEXT)
        }
        fun tail(frame: Int, channel: Int) = previousTail[(tailFrames - context + frame) * channels + channel].toInt()
        fun next(frame: Int, channel: Int) = head[frame * channels + channel].toInt()
        for (channel in 0 until channels) {
            originalBoundary = maxOf(originalBoundary, abs(next(0, channel) - tail(context - 1, channel)))
            originalPeak = maxOf(originalPeak, abs(next(0, channel) - tail(context - 1, channel)))
            for (i in 1..window + 1) originalPeak = maxOf(originalPeak, abs(next(i, channel) - next(i - 1, channel)))
        }
        val localSlopeLimit = ceil(24.0 * 48_000 / currentFormat.sampleRate).toInt()
        var anyChanged = false
        var peakCorrection = 0
        var plannedBoundary = 0
        var plannedPeak = 0
        for (channel in 0 until channels) {
            val left = tail(context - 1, channel)
            val leftSlope = left - tail(context - 2, channel)
            val right = next(window, channel)
            val rightSlope = next(window + 1, channel) - right
            val jump = next(0, channel) - left
            val slopes = (1 until context).map { tail(it, channel) - tail(it - 1, channel) } +
                (1..window + context).map { next(it, channel) - next(it - 1, channel) }
            val neighborStep = slopes.maxOf { abs(it) }
            var channelChanged = false
            if (abs(jump) > maxOf(8, neighborStep * 2)) {
                val tailPeak = (0 until context).maxOf { abs(tail(it, channel)) }
                val headPeak = (0..window + context).maxOf { abs(next(it, channel)) }
                if (maxOf(tailPeak, headPeak) > 24_576) return unchanged(Reason.NEAR_CLIPPING)
                if (minOf(tailPeak, headPeak) < 128 || maxOf(tailPeak, headPeak) > minOf(tailPeak, headPeak) * 2) {
                    return unchanged(Reason.QUIET_OR_ONSET)
                }
                if (neighborStep > localSlopeLimit || slopes.maxOrNull()!! - slopes.minOrNull()!! > maxOf(2, localSlopeLimit / 4)) {
                    return unchanged(Reason.TRANSIENT_OR_HIGH_SLOPE)
                }
                if (abs(jump) !in 64..512 || abs(jump) < neighborStep * 8) return unchanged(Reason.AMBIGUOUS_BOUNDARY)
                val delta = right - left
                if (delta == 0) return unchanged(Reason.AMBIGUOUS_BOUNDARY)
                val direction = if (delta > 0) 1 else -1
                if (leftSlope * direction < 0 || rightSlope * direction < 0) return unchanged(Reason.REVERSAL)
                val span = window + 1.0
                var previous = left
                for (i in 0 until window) {
                    val t = (i + 1) / span
                    val t2 = t * t
                    val t3 = t2 * t
                    val value = (2 * t3 - 3 * t2 + 1) * left + (t3 - 2 * t2 + t) * span * leftSlope +
                        (-2 * t3 + 3 * t2) * right + (t3 - t2) * span * rightSlope
                    // Reject overshoot BEFORE rounding; never clamp an invalid interpolation.
                    if (!value.isFinite() || value < minOf(left, right) || value > maxOf(left, right) ||
                        value < Short.MIN_VALUE || value > Short.MAX_VALUE) return unchanged(Reason.CANDIDATE_OVERSHOOT)
                    val sample = value.roundToInt()
                    if ((sample - previous) * direction < 0) return unchanged(Reason.REVERSAL)
                    val correction = abs(sample - next(i, channel))
                    if (correction > minOf(512, minOf(tailPeak, headPeak) / 2, abs(jump))) {
                        return unchanged(Reason.CORRECTION_TOO_LARGE)
                    }
                    peakCorrection = maxOf(peakCorrection, correction)
                    output[i * channels + channel] = sample.toShort()
                    channelChanged = channelChanged || sample != next(i, channel)
                    previous = sample
                }
                if ((right - previous) * direction < 0) return unchanged(Reason.REVERSAL)
                val firstStep = output[channel].toInt() - left
                val lastStep = right - output[(window - 1) * channels + channel].toInt()
                // Discrete, rounded endpoint steps, not merely the polynomial's derivatives.
                val endpointTolerance = ceil(6.0 * abs(delta) / (span * span)).toInt() + maxOf(1, neighborStep / 4)
                if (abs(firstStep - leftSlope) > endpointTolerance || abs(lastStep - rightSlope) > endpointTolerance ||
                    abs(firstStep - leftSlope) >= abs(jump - leftSlope)) return unchanged(Reason.CANDIDATE_SLOPE)
                val candidateSteps = listOf(firstStep) + (1 until window).map {
                    output[it * channels + channel].toInt() - output[(it - 1) * channels + channel].toInt()
                } + lastStep
                val candidatePeak = candidateSteps.maxOf { abs(it) }
                val stepBudget = maxOf(neighborStep * 2, ceil(3.0 * abs(delta) / span).toInt())
                if (candidatePeak >= abs(jump) || candidatePeak > stepBudget) return unchanged(Reason.CANDIDATE_STEP)
            }
            anyChanged = anyChanged || channelChanged
            plannedBoundary = maxOf(plannedBoundary, abs(output[channel].toInt() - left))
            plannedPeak = maxOf(plannedPeak, abs(output[channel].toInt() - left))
            for (i in 1..window + 1) plannedPeak = maxOf(plannedPeak,
                abs(output[i * channels + channel].toInt() - output[(i - 1) * channels + channel].toInt()))
        }
        if (!anyChanged) return unchanged(Reason.CONTINUOUS)
        if (plannedPeak > originalPeak || plannedBoundary >= originalBoundary) return unchanged(Reason.CANDIDATE_STEP)
        return Plan(Reason.REPAIRED, output, head.size, output.size, frames, frames, window,
            window, peakCorrection, originalBoundary, plannedBoundary, originalPeak, plannedPeak)
    }
}
