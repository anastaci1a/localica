package io.github.anastaci1a.localica.ui

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** Physical displayed dimensions, before orientation and live selection-position placement. */
internal data class SelectorReturnFrame(
    val shellTravel: Float, val shellCross: Float,
    val lensTravel: Float, val lensCross: Float,
    val gap: Float, val corner: Float, val outerCorner: Float, val shellStart: Float,
) {
    init {
        require(values().all { it.isFinite() })
        require(shellTravel > 0f && shellCross > 0f && lensTravel > 0f && lensCross > 0f)
        require(lensCross <= shellCross && gap >= 0f && corner >= 0f && outerCorner >= 0f)
    }

    internal fun values() = floatArrayOf(shellTravel, shellCross, lensTravel, lensCross,
        gap, corner, outerCorner, shellStart)

    fun toward(other: SelectorReturnFrame, fraction: Float): SelectorReturnFrame {
        val t = fraction.coerceIn(0f, 1f)
        if (t == 0f) return this
        if (t == 1f) return other
        val a = values(); val b = other.values()
        return from(FloatArray(a.size) { a[it] + (b[it] - a[it]) * t })
    }

    internal companion object {
        fun from(v: FloatArray) = SelectorReturnFrame(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7])
    }
}

/** All budgets are physical, never a percentage of a long vertical or horizontal track. */
internal data class SelectorReturnLimits(val extension: Float, val inset: Float, val wall: Float) {
    init { require(extension.isFinite() && extension > 0f && inset.isFinite() && inset > 0f && wall.isFinite()) }
    // Choice layout rounds its inset to whole pixels; full held contact must fit too.
    val crossClearance get() = maxOf(extension, inset) // per side of the resting shell
    internal fun mode(): FloatArray {
        // Reserve headroom below the 6 dp lens limit for the captured residual.
        // Circular and narrow selectors retain this same nonzero signed mode; their
        // uniform radius follows the smaller final dimension during release.
        val lens = minOf(inset, extension)
        val shellCross = -2f * extension
        val lensCross = -lens
        val gap = (shellCross - lensCross) / 2f
        return floatArrayOf(extension, shellCross, lens, lensCross, gap,
            lensCross / 2f, shellCross / 2f, extension * (wall.coerceIn(-1f, 1f) - 1f) / 2f)
    }
}

internal fun selectorCriticalResidual(seconds: Float): Float {
    require(seconds.isFinite() && seconds >= 0f)
    val omega = sqrt(380f)
    return (1f + omega * seconds) * exp(-omega * seconds)
}

/** Exact spring step: changing damping preserves the signed mode's displacement and velocity. */
internal data class SelectorDampedMode(val value: Float, val velocity: Float = 0f) {
    fun advance(seconds: Float, damping: Float): SelectorDampedMode {
        require(seconds.isFinite() && seconds >= 0f)
        if (seconds == 0f) return this
        val drag = (damping.takeIf { it.isFinite() } ?: 8f).coerceIn(2f, 40f)
        val decay = drag / 2f
        val discriminant = 380f - decay * decay
        if (abs(discriminant) < .001f) {
            val b = velocity + decay * value
            val envelope = exp(-decay * seconds)
            return SelectorDampedMode((value + b * seconds) * envelope,
                (velocity - decay * b * seconds) * envelope)
        }
        if (discriminant > 0f) {
            val frequency = sqrt(discriminant)
            val b = (velocity + decay * value) / frequency
            val cosine = cos(frequency * seconds); val sine = sin(frequency * seconds)
            val envelope = exp(-decay * seconds)
            return SelectorDampedMode(envelope * (value * cosine + b * sine),
                envelope * (velocity * cosine - (decay * b + frequency * value) * sine))
        }
        val root = sqrt(-discriminant)
        val first = -decay + root; val second = -decay - root
        val a = (velocity - second * value) / (first - second)
        val b = value - a
        val x = a * exp(first * seconds); val y = b * exp(second * seconds)
        return SelectorDampedMode(x + y, first * x + second * y)
    }
}

/**
 * One signed mode for both footprints: travel and cross extents have opposite phase.
 * X = rest + B*q + (start - rest - B*q0)*critical(t). The residual preserves the
 * exact displayed release contour, including a held lens's fixed cross extent.
 * Only this owner supplies release dimensions; GlassMomentum can still supply optical depth.
 * Callers scale elapsed time once. Re-grab uses the same critical bridge to its live held target.
 */
internal class SelectorCrossReturn(
    val start: SelectorReturnFrame,
    val rest: SelectorReturnFrame,
    private val limits: SelectorReturnLimits,
) {
    private val basis = limits.mode()
    private val origin = rest.values()
    private val delta = start.values().mapIndexed { i, value -> value - origin[i] }
    // A pulled shell is the physical driver. Otherwise normalize the moving lens by
    // its small physical budget; a long track must not dominate the mode projection.
    private val shellExcitation = if (basis[0] > 0f) (delta[0] / basis[0] + delta[1] / basis[1]) / 2f else 0f
    private val initial = (if (abs(shellExcitation) > .0001f) shellExcitation
        else if (basis[2] > 0f) (delta[2] / basis[2] + delta[3] / basis[3]) / 2f else 0f).coerceIn(-1f, 1f)
    private val residual = FloatArray(basis.size) { delta[it] - basis[it] * initial }
    internal var mode = SelectorDampedMode(initial)
        private set
    private var elapsed = 0f
    private var finished = false
    val settled get() = finished
    private val quiet get() = abs(mode.value) < .0003f && abs(mode.velocity) < .003f &&
        selectorCriticalResidual(elapsed) < .0003f
    internal var physicalScale = 1f
        private set

    val frame: SelectorReturnFrame get() {
        if (elapsed == 0f) return start
        if (settled) return rest
        val critical = selectorCriticalResidual(elapsed)
        val value = FloatArray(basis.size) { origin[it] + basis[it] * mode.value + residual[it] * critical }
        // Apply one common scale to the complete displacement if a physical constraint
        // is reached. Independent width/height clipping would change phase and timing.
        var scale = 1f
        fun bound(index: Int, low: Float, high: Float) {
            val change = value[index] - origin[index]
            if (value[index] < low && change < 0f) scale = minOf(scale, (low - origin[index]) / change)
            if (value[index] > high && change > 0f) scale = minOf(scale, (high - origin[index]) / change)
        }
        fun contain(restMargin: Float, margin: Float) {
            if (margin < 0f) scale = minOf(scale, restMargin / (restMargin - margin))
        }
        bound(0, maxOf(.001f, rest.shellTravel - limits.extension), rest.shellTravel + limits.extension)
        bound(1, maxOf(.001f, rest.shellCross - 2f * limits.crossClearance), rest.shellCross + 2f * limits.crossClearance)
        val lensBudget = 1.5f * minOf(limits.inset, limits.extension)
        bound(2, maxOf(.001f, minOf(start.lensTravel, rest.lensTravel - lensBudget)),
            maxOf(start.lensTravel, rest.lensTravel + lensBudget))
        bound(3, maxOf(.001f, minOf(start.lensCross, rest.lensCross - lensBudget)),
            maxOf(start.lensCross, rest.lensCross + lensBudget))
        bound(4, 0f, rest.gap + limits.extension)
        bound(5, 0f, Float.MAX_VALUE); bound(6, 0f, Float.MAX_VALUE)
        contain(rest.shellCross - rest.lensCross, value[1] - value[3])
        contain(rest.shellTravel - rest.lensTravel - rest.gap, value[0] - value[2] - value[4])
        fun projected(fraction: Float): SelectorReturnFrame {
            val v = FloatArray(value.size) { origin[it] + (value[it] - origin[it]) * fraction }
            v[5] = v[5].coerceIn(0f, minOf(v[2], v[3]) / 2f)
            v[6] = v[6].coerceIn(0f, minOf(v[0], v[1]) / 2f)
            val shift = rest.shellTravel - v[0]
            v[7] = v[7].coerceIn(minOf(0f, shift), maxOf(0f, shift))
            return SelectorReturnFrame.from(v)
        }
        physicalScale = scale.coerceIn(0f, 1f)
        var output = projected(physicalScale)
        if (!selectorReturnRoundedContains(output)) {
            // Radius clamping permits circular lenses to trade width for height.
            // Only a genuinely escaping contour limits the complete shared mode.
            var low = 0f; var high = physicalScale
            repeat(24) {
                val middle = (low + high) / 2f
                if (selectorReturnRoundedContains(projected(middle))) low = middle else high = middle
            }
            physicalScale = low
            output = projected(low)
        }
        return output
    }

    fun advance(seconds: Float, damping: Float = 8f): SelectorReturnFrame {
        require(seconds.isFinite() && seconds >= 0f)
        if (finished) return rest
        elapsed += seconds
        mode = mode.advance(seconds, damping)
        if (quiet) { finished = true; mode = SelectorDampedMode(0f) }
        return frame
    }
}

/**
 * Both end corners must fit the outer rounded rectangle eroded by the lens radius.
 * Positive parts restrict the circle test to its active corner quadrant; deep inset
 * corners in either straight strip already fit. Endpoint placements bound all cells
 * between them because their distances to either shell end only increase.
 */
internal fun selectorReturnRoundedContains(frame: SelectorReturnFrame): Boolean {
    val crossGap = (frame.shellCross.toDouble() - frame.lensCross) / 2.0
    val farGap = frame.shellTravel.toDouble() - frame.lensTravel - frame.gap
    if (crossGap < 0.0 || frame.gap < 0f || farGap < 0.0) return false
    val radius = frame.outerCorner.toDouble() - frame.corner
    if (radius <= 0.0) return true
    val cross = maxOf(radius - crossGap, 0.0)
    return listOf(frame.gap.toDouble(), farGap).all { gap ->
        val travel = maxOf(radius - gap, 0.0)
        travel * travel + cross * cross <= radius * radius
    }
}
