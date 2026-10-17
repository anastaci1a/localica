package io.github.anastaci1a.localica.ui

import kotlin.math.abs
import kotlin.math.hypot

internal object ControlScrollAxis {
    const val None = 0
    const val Horizontal = 1
    const val Vertical = 2
}

internal enum class ControlGestureOwner { Pending, Control, Scroll, Cancelled }

/** Resolve only the beginning of a contact; later speed/reversal cannot steal it.
 * The response window is application tuning, not a platform velocity standard. */
internal class ControlGestureIntent(
    private val slop: Float,
    private val scrollAxes: Int,
    private val controlAxis: Int = ControlScrollAxis.None,
    private val responseMillis: Long = 120L,
) {
    var owner = ControlGestureOwner.Pending
        private set

    fun move(dx: Float, dy: Float, elapsedMillis: Long): ControlGestureOwner {
        if (owner != ControlGestureOwner.Pending) return owner
        val distance = hypot(dx, dy)
        val x = abs(dx); val y = abs(dy)
        if (maxOf(x, y) <= slop) return owner
        val parentTravel = maxOf(
            if (scrollAxes and ControlScrollAxis.Horizontal != 0) x else 0f,
            if (scrollAxes and ControlScrollAxis.Vertical != 0) y else 0f,
        )
        val controlTravel = when (controlAxis) {
            ControlScrollAxis.Horizontal -> x
            ControlScrollAxis.Vertical -> y
            else -> distance
        }
        val crossAxis = controlAxis != ControlScrollAxis.None && parentTravel > controlTravel * 1.2f
        // Two slop lengths per response window separates prompt sweeps from
        // deliberate manipulation. A stationary hold contributes to intent.
        val prompt = elapsedMillis < responseMillis &&
            parentTravel * responseMillis > 2f * slop * elapsedMillis.coerceAtLeast(1)
        owner = if (parentTravel > slop && (crossAxis || prompt)) ControlGestureOwner.Scroll
            else ControlGestureOwner.Control
        return owner
    }

    /** Deliberate stationary contact can acquire without inventing another movement policy. */
    fun hold() { if (owner == ControlGestureOwner.Pending) owner = ControlGestureOwner.Control }

    fun cancel() { owner = ControlGestureOwner.Cancelled }
}
