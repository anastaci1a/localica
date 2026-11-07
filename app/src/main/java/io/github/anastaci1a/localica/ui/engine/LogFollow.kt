package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Offset
import kotlin.math.expm1
import kotlin.math.ln1p

/**
 * Logarithmic follow for a held item in an immutable list (#640, operator answer H3):
 * o(d) = k·ln(1 + d/k). o(0) = 0 and o′(0) = 1, so the item starts locked to the finger with no jump,
 * then resists with increasing distance (k = 12 dp: 50 → 19.7, 200 → 34.5, 400 → 42.4). Pure maths; the
 * owner drives its follow channel with [follow] and hands [velocity] to the `Damped` spring-back on release.
 */
object LogFollow {
    /** Offset magnitude for finger distance [d] ≥ 0 and scale [k] > 0, both in the same unit. */
    fun offset(d: Float, k: Float): Float {
        checkScale(k); require(d >= 0f && d.isFinite()) { "distance must be finite and non-negative" }
        return (k.toDouble() * ln1p(d.toDouble() / k)).toFloat()
    }

    /** do/dd = 1 / (1 + d/k): the gain applied to radial finger velocity (I2 chain rule). */
    fun slope(d: Float, k: Float): Float {
        checkScale(k); require(d >= 0f && d.isFinite()) { "distance must be finite and non-negative" }
        return (1.0 / (1.0 + d.toDouble() / k)).toFloat()
    }

    /** Finger distance that yields offset [o]: d = k·(e^(o/k) − 1). */
    fun inverse(o: Float, k: Float): Float {
        checkScale(k); require(o >= 0f && o.isFinite()) { "offset must be finite and non-negative" }
        return (k.toDouble() * expm1(o.toDouble() / k)).toFloat()
    }

    /** Item offset along the finger vector [delta] (finger position minus the down position). */
    fun follow(delta: Offset, k: Float): Offset {
        val d = delta.getDistance()
        if (d == 0f) return Offset.Zero
        val ratio = offset(d, k) / d
        return Offset(delta.x * ratio, delta.y * ratio)
    }

    /**
     * Item velocity for finger velocity [fingerVelocity] at finger offset [delta]: the radial component scales by
     * [slope], the tangential component by o(d)/d. At d = 0 both gains are 1, so the item moves with the finger.
     */
    fun velocity(delta: Offset, fingerVelocity: Offset, k: Float): Offset {
        val d = delta.getDistance()
        if (d == 0f) return fingerVelocity
        val ux = delta.x / d; val uy = delta.y / d
        val radial = fingerVelocity.x * ux + fingerVelocity.y * uy
        val tangentialGain = offset(d, k) / d
        val radialGain = slope(d, k)
        val tx = fingerVelocity.x - radial * ux; val ty = fingerVelocity.y - radial * uy
        return Offset(tx * tangentialGain + radial * radialGain * ux, ty * tangentialGain + radial * radialGain * uy)
    }

    private fun checkScale(k: Float) { require(k > 0f && k.isFinite()) { "follow scale must be finite and positive" } }
}
