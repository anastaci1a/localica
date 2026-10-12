package io.github.anastaci1a.localica.ui

import kotlin.math.abs
import kotlin.math.exp

/** A damped elastic body driven by changes in travel velocity, not a second animation. */
internal class GlassMomentum {
    var strain = 0f
        private set
    private var strainVelocity = 0f
    private var travelVelocity = 0f
    val settled get() = abs(strain) < .0002f && abs(strainVelocity) < .002f
    val scaleX get() = exp(strain)
    val scaleY get() = exp(-strain) // Constant projected area during squash/stretch.

    fun reset() { strain = 0f; strainVelocity = 0f; travelVelocity = 0f }

    fun advance(velocity: Float, seconds: Float) {
        if (seconds <= 0f) return
        // An acceleration impulse stretches the body; stopping produces the opposite
        // compression. Clamp impulses for interruptions and dropped/paused frames.
        strainVelocity += ((velocity - travelVelocity) * .11f).coerceIn(-.7f, .7f)
        travelVelocity = velocity
        var remaining = seconds.coerceAtMost(.064f)
        while (remaining > 0f) {
            val dt = minOf(remaining, 1f / 240f)
            strainVelocity += (-240f * strain - 25f * strainVelocity) * dt
            strain = (strain + strainVelocity * dt).coerceIn(-.065f, .065f)
            remaining -= dt
        }
    }
}
