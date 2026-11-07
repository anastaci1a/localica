package io.github.anastaci1a.localica

import kotlin.math.abs
import org.junit.Assert.assertEquals

/** Independent cubic boundary-value oracle for ink carried by the #608 lyrics phase.
 * Time and per-frame travel belong to the owner (ReleaseLawOracle); this oracle specifies
 * the value and travel derivative at each polarity/reversal join, then the target at rest.
 * It has no duration or minimum intermediate-frame count. */
internal class LyricsPhaseReference(initial: Float, phase: Float, end: Float) {
    private var start = phase
    private var finish = end
    private var value = initial
    private var target = initial
    private var slope = 0f
    private var endSlope = 0f

    private fun sample(phase: Float): Pair<Float, Float> {
        val length = abs(finish - start)
        if (length < 1e-3f) return target to 0f
        val u = (abs(phase - start) / length).coerceIn(0f, 1f)
        // Polynomial coefficients solve the four endpoint value/derivative constraints.
        val c = slope * length
        val b = 3f * (target - value) - 2f * c - endSlope * length
        val a = 2f * (value - target) + c + endSlope * length
        return (((a * u + b) * u + c) * u + value).coerceIn(0f, 1f) to
            ((3f * a * u + 2f * b) * u + c) / length
    }

    fun join(next: Float, phase: Float, end: Float) {
        val (shown, derivative) = sample(phase)
        if (next == target && (finish == end || shown == target)) return
        value = shown; slope = derivative; target = next; start = phase; finish = end
        endSlope = 0f
        if (abs(end - phase) > 1e-3f && derivative == 0f && abs(phase - (1f - end)) <= 1e-3f) {
            slope = (next - shown) / abs(end - phase); endSlope = slope
        }
    }

    fun expected(phase: Float): Float = if (abs(phase - finish) <= 1e-3f) target else sample(phase).first

    fun assertFrame(label: String, phase: Float, shown: Float) =
        assertEquals("$label follows the lyrics phase $phase", expected(phase), shown, .015f)
}
