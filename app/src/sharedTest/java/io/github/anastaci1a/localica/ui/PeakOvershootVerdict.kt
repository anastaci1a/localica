package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.max

/** #854 round 3 (R2-2): the verdict of a near-full-height popup's sampled flight, shared by the native test and its JVM proof that
 * the verdict cannot pass vacuously. [rest] is the settled rectangle, [start] the trigger, [kappa] the reachable overshoot fraction. */
internal data class PeakOvershootVerdict(val failures: List<String>, val peakBeyondRest: Float, val beyondWindow: Float)

internal fun peakOvershootVerdict(samples: List<Rect>, rest: Rect, start: Rect, window: Rect, marginPx: Float, kappa: Float): PeakOvershootVerdict {
    val failures = mutableListOf<String>()
    if (!(rest.top >= window.top + marginPx - 1 && rest.bottom <= window.bottom - marginPx + 1 &&
            rest.left >= window.left + marginPx - 1 && rest.right <= window.right - marginPx + 1)) failures += "rest $rest is inside the $marginPx px margin of $window"
    if (rest.height < window.height * .6f) failures += "rest $rest is not near-full-height in $window"
    // The flight must have been seen: enough samples, some still smaller than rest, and a real overshoot past it.
    if (samples.size < 10) failures += "only ${samples.size} samples: the flight was not observed"
    if (samples.none { it.width < rest.width - .5f || it.height < rest.height - .5f }) failures += "no sample smaller than rest: the opening was not observed"
    var peak = 0f; var beyondWindow = 0f
    for (r in samples) {
        if (!(listOf(r.left, r.top, r.right, r.bottom).all { it.isFinite() } && r.width >= 1f && r.height >= 1f)) failures += "degenerate sample $r"
        val excess = listOf(rest.left - r.left to abs(rest.left - start.left), rest.top - r.top to abs(rest.top - start.top),
            r.right - rest.right to abs(rest.right - start.right), r.bottom - rest.bottom to abs(rest.bottom - start.bottom))
        for ((beyond, travel) in excess) if (beyond > kappa * travel + 2f) failures += "overshoot $beyond > kappa $kappa x travel $travel at $r"
        peak = max(peak, excess.maxOf { it.first })
        beyondWindow = max(beyondWindow, listOf(window.top - r.top, r.bottom - window.bottom, window.left - r.left, r.right - window.right).max())
    }
    // Sub-pixel rounding is not an overshoot; a near-full-height spring opening passes rest by tens of pixels.
    if (peak < 1f) failures += "no overshoot past rest was observed"
    if (samples.lastOrNull() != rest) failures += "the flight did not settle back on its rest rectangle"
    return PeakOvershootVerdict(failures, peak, beyondWindow)
}
