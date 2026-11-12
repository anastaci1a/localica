package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

/** #854 round 3 (R2-2): the native peak-overshoot verdict cannot pass vacuously. Traces that never saw the flight, took "rest"
 * mid-flight or overshot past kappa x travel fail; a real opening with a small overshoot that settles passes. */
class PeakOvershootVerdictTest {
    private val window = Rect(0f, 0f, 1080f, 2340f)
    private val margin = 29.25f
    private val start = Rect(480f, 2120f, 600f, 2184f)
    private val rest = Rect(margin + 40f, margin + 100f, 1080f - margin - 40f, 2340f - margin - 100f)
    private val kappa = .0275f

    /** A damped opening from the trigger to rest that overshoots by [over] of each edge's travel at its peak, then settles. */
    private fun trace(over: Float, frames: Int = 40): List<Rect> = (1..frames).map { i ->
        val t = i / frames.toFloat()
        val p = if (t < .6f) t / .6f * (1 + over) else 1 + over * (1 - (t - .6f) / .3f).coerceIn(0f, 1f)
        fun lerp(a: Float, b: Float) = a + (b - a) * p
        Rect(lerp(start.left, rest.left), lerp(start.top, rest.top), lerp(start.right, rest.right), lerp(start.bottom, rest.bottom))
    }.let { it.dropLast(1) + rest }

    @Test fun aRealOpeningWithASmallOvershootPasses() {
        val verdict = peakOvershootVerdict(trace(.015f), rest, start, window, margin, kappa)
        assertEquals(emptyList<String>(), verdict.failures)
        assertTrue(verdict.peakBeyondRest > 0f)
    }

    @Test fun vacuousTracesFail() {
        // Only rest frames: the old test passed this with peakBeyondRest = 0.
        assertFalse(peakOvershootVerdict(List(3) { rest }, rest, start, window, margin, kappa).failures.isEmpty())
        assertFalse(peakOvershootVerdict(List(30) { rest }, rest, start, window, margin, kappa).failures.isEmpty())
        // "Rest" taken mid-opening, before the engine settled: nothing passes it.
        val early = trace(.015f).take(15)
        assertTrue(peakOvershootVerdict(early, early.last(), start, window, margin, kappa).failures.any { "no overshoot" in it })
        // No overshoot at all (a non-spring opening) is not proof of the flight either.
        assertTrue(peakOvershootVerdict(trace(0f), rest, start, window, margin, kappa).failures.any { "no overshoot" in it })
    }

    @Test fun anOvershootPastKappaFails() {
        assertTrue(peakOvershootVerdict(trace(.06f), rest, start, window, margin, kappa).failures.any { "kappa" in it })
    }

    @Test fun aRestInsideTheMarginFails() {
        val tight = Rect(10f, 10f, 1070f, 2330f)
        assertTrue(peakOvershootVerdict(List(12) { tight }, tight, start, window, margin, kappa).failures.any { "margin" in it })
    }
}
