package io.github.anastaci1a.localica.ui

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.tanh

/** Logarithmic deformation of an approximate constant-volume cap, not a fluid simulation. */
internal data class GlassShape(val strain: Float = 0f, val depth: Float = 0f) {
    fun scaleX(vertical: Boolean = false) = exp((if (vertical) -strain else strain) - depth / 2)
    fun scaleY(vertical: Boolean = false) = exp((if (vertical) strain else -strain) - depth / 2)
    val thickness get() = exp(depth)
}

/** Compensation for center-based scaling: the contacting edge stays on its wall. */
internal fun glassWallTranslation(extent: Float, scale: Float, wall: Float): Float =
    wall.coerceIn(-1f, 1f) * extent * (1f - scale) / 2

/** Approach wall anchoring continuously; switching anchors at the endpoint jumps
 * whenever contact has already expanded the body. Works in either travel axis. */
internal fun glassWallAffinity(position: Float, last: Float): Float {
    if (last <= 0f) return 0f
    val reach = minOf(.25f, last / 2f)
    fun ease(t: Float): Float { val p = t.coerceIn(0f, 1f); return p * p * (3f - 2f * p) }
    return ease((position - last + reach) / reach) - ease((reach - position) / reach)
}

/** A sign change contains braking that magnitude-only velocity misses. The
 * correction vanishes with crossing speed, leaving a slow passage through zero
 * continuous. Callers supply finite bounded speeds and clamp each channel impulse. */
internal fun glassMotionSpeedChange(previous: Float, current: Float): Float {
    val change = abs(current) - abs(previous)
    return if (previous * current < 0f) change - 2f * minOf(abs(previous), abs(current)) else change
}

/** Position is owned by the gesture; acceleration, contact and pressure drive its shape. */
internal class GlassMomentum(var damping: Float = 8f) {
    private class Spring {
        var value = 0f
        var velocity = 0f
        var target = 0f
        val settled get() = abs(value - target) < .0003f && abs(velocity) < .003f
        fun advance(dt: Float, damping: Float, held: Boolean = false) {
            val before = value
            velocity += (380f * (target - value) - damping * velocity) * dt
            value += velocity * dt
            // A held finger constrains the surface: it stores strain, never rings.
            if (held) {
                value = value.coerceIn(minOf(before, target), maxOf(before, target))
                if (value == target || value == before) velocity = 0f
            }
        }
        fun reset() { value = 0f; velocity = 0f; target = 0f }
        fun blendFrom(a: Spring, b: Spring, weight: Float, aAxis: Float = 1f, bAxis: Float = 1f) {
            value = a.value * aAxis * (1f - weight) + b.value * bAxis * weight
            velocity = a.velocity * aAxis * (1f - weight) + b.velocity * bAxis * weight
            target = a.target * aAxis * (1f - weight) + b.target * bAxis * weight
        }
    }
    private val motion = Spring()
    private val pressure = Spring()
    private val depth = Spring()
    private var travelVelocity = 0f
    val strain get() = .12f * tanh(motion.value / .12f) + pressure.value
    val shape get() = GlassShape(strain, .24f * tanh(depth.value / .24f))
    val scaleX get() = shape.scaleX()
    val scaleY get() = shape.scaleY()
    val settled get() = motion.settled && pressure.settled && depth.settled

    fun reset() { motion.reset(); pressure.reset(); depth.reset(); travelVelocity = 0f }

    /** A separating/rejoining body transfers stored energy as well as its shape.
     * Copying only the visible scale would discard the next frame's rebound. */
    fun blendFrom(a: GlassMomentum, b: GlassMomentum = a, weight: Float = 0f,
        aAxis: Float = 1f, bAxis: Float = 1f) {
        val fraction = weight.coerceIn(0f, 1f)
        motion.blendFrom(a.motion, b.motion, fraction, aAxis, bAxis)
        pressure.blendFrom(a.pressure, b.pressure, fraction, aAxis, bAxis)
        depth.blendFrom(a.depth, b.depth, fraction)
        travelVelocity = a.travelVelocity + (b.travelVelocity - a.travelVelocity) * fraction
    }

    fun blendedShape(other: GlassMomentum, weight: Float, otherAxis: Float = 1f): GlassShape {
        val fraction = weight.coerceIn(0f, 1f)
        fun mix(a: Float, b: Float) = a + (b - a) * fraction
        return GlassShape(.12f * tanh(mix(motion.value, other.motion.value * otherAxis) / .12f) +
            mix(pressure.value, other.pressure.value * otherAxis), .24f * tanh(mix(depth.value, other.depth.value) / .24f))
    }

    fun advance(velocity: Float, seconds: Float, pressure: Float = 0f, contact: Float = 0f, impulseScale: Float = 1f) {
        if (!seconds.isFinite() || seconds <= 0f) return
        val speed = if (velocity.isFinite()) velocity.coerceIn(-30f, 30f) else 0f
        val speedChange = glassMotionSpeedChange(travelVelocity, speed)
        // Strain is axial: acceleration stretches and braking compresses along
        // travel, independent of whether that travel is leftward or rightward.
        motion.velocity += (speedChange * .11f * impulseScale.coerceIn(1f, 1.8f)).coerceIn(-.7f, .7f)
        // Acceleration flattens the cap; braking lets it rise. Contact sustains flattening.
        depth.velocity -= (speedChange * .035f * impulseScale.coerceIn(1f, 1.8f)).coerceIn(-.35f, .35f)
        travelVelocity = speed
        this.pressure.target = if (pressure.isFinite()) pressure.coerceIn(-.22f, .12f) else 0f
        val held = contact.isFinite() && contact > 0f || abs(this.pressure.target) > .0001f
        depth.target = -.24f * (if (contact.isFinite()) contact.coerceIn(0f, 1f) else 0f) - abs(this.pressure.target) * .3f
        val drag = if (damping.isFinite()) damping.coerceIn(2f, 40f) else 8f
        var remaining = seconds.coerceAtMost(.1f)
        while (remaining > 0f) {
            val dt = minOf(remaining, 1f / 240f)
            val resistance = if (held) maxOf(40f, drag) else drag
            // Motion is driven by velocity impulses around a zero target. Projecting
            // it into [before, target] would erase acceleration at rest; held
            // critical damping already prevents an unforced ringing response.
            motion.advance(dt, resistance); this.pressure.advance(dt, resistance, held); depth.advance(dt, resistance, held)
            remaining -= dt
        }
    }
}
