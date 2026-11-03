package io.github.anastaci1a.localica.ui

import kotlin.math.abs

internal object ControlScrollAxis {
    const val None = 0
    const val Horizontal = 1
    const val Vertical = 2
}

/** Finger travel an ancestor can still turn into scrolling or surface movement. */
internal object ControlScrollDirection {
    const val None = 0
    const val Up = 1
    const val Down = 2
    const val Left = 4
    const val Right = 8
    const val Vertical = Up or Down
    const val Horizontal = Left or Right
}

/** One stationary contact this long locks onto its control, for every control type. */
internal const val ControlHoldLockMillis = 250L

internal enum class ControlGestureOwner { Pending, Control, Scroll, Cancelled }

/** Resolve only the beginning of a contact; later speed/reversal cannot steal it.
 *
 * Before the hold threshold, travel whose dominant direction an ancestor can still move
 * belongs to that ancestor; any other travel belongs to the control. A contact held still
 * for the threshold belongs to the control. The decision depends only on event positions
 * and times, so every observer of one contact resolves it identically. */
internal class ControlGestureIntent(
    private val slop: Float,
    private val directions: Int,
    private val holdMillis: Long = ControlHoldLockMillis,
) {
    var owner = ControlGestureOwner.Pending
        private set

    fun move(dx: Float, dy: Float, elapsedMillis: Long): ControlGestureOwner {
        if (owner != ControlGestureOwner.Pending) return owner
        val x = abs(dx); val y = abs(dy)
        if (maxOf(x, y) <= slop) return owner
        owner = if (elapsedMillis >= holdMillis) ControlGestureOwner.Control
            else if (directions and direction(dx, dy) != 0) ControlGestureOwner.Scroll
            else ControlGestureOwner.Control
        return owner
    }

    /** A stationary contact reached the hold threshold. */
    fun hold() { if (owner == ControlGestureOwner.Pending) owner = ControlGestureOwner.Control }

    fun cancel() { owner = ControlGestureOwner.Cancelled }

    companion object {
        /** Dominant finger direction; ties count as vertical, the common scroll axis. */
        fun direction(dx: Float, dy: Float): Int =
            if (abs(dy) >= abs(dx)) { if (dy < 0f) ControlScrollDirection.Up else ControlScrollDirection.Down }
            else if (dx < 0f) ControlScrollDirection.Left else ControlScrollDirection.Right
    }
}

/** Finger directions that move a scrollable's content, from its live edge state. */
internal fun controlScrollDirections(vertical: Boolean, canScrollForward: Boolean, canScrollBackward: Boolean,
    reverse: Boolean): Int {
    // Unreversed, a finger travelling toward the start (up/left) reveals later content.
    val towardStart = if (vertical) ControlScrollDirection.Up else ControlScrollDirection.Left
    val towardEnd = if (vertical) ControlScrollDirection.Down else ControlScrollDirection.Right
    val forward = if (reverse) towardEnd else towardStart
    val backward = if (reverse) towardStart else towardEnd
    return (if (canScrollForward) forward else 0) or (if (canScrollBackward) backward else 0)
}
