package io.github.anastaci1a.localica.ui

import kotlin.math.abs
import kotlin.math.exp

/** A damped elastic body driven by changes in travel velocity, not a second animation. */
internal class GlassMomentum {
    var strain = 0f
        private set
    private var motionStrain = 0f
    private var pressureStrain = 0f
    private var pressureVelocity = 0f
    private var pressureTarget = 0f
    private var strainVelocity = 0f
    private var travelVelocity = 0f
    val settled get() = abs(motionStrain) < .0002f && abs(strainVelocity) < .002f &&
        abs(pressureStrain - pressureTarget) < .0002f && abs(pressureVelocity) < .002f
    val scaleX get() = exp(strain)
    val scaleY get() = exp(-strain) // Constant projected area during squash/stretch.

    fun reset() { strain = 0f; motionStrain = 0f; pressureStrain = 0f; pressureVelocity = 0f; pressureTarget = 0f; strainVelocity = 0f; travelVelocity = 0f }

    fun advance(velocity: Float, seconds: Float, pressure: Float = 0f) {
        if (seconds <= 0f) return
        // An acceleration impulse stretches the body; stopping produces the opposite
        // compression. Clamp impulses for interruptions and dropped/paused frames.
        strainVelocity += ((velocity - travelVelocity) * .11f).coerceIn(-.7f, .7f)
        travelVelocity = velocity
        pressureTarget = pressure.coerceIn(-.22f, .12f)
        var remaining = seconds.coerceAtMost(.064f)
        while (remaining > 0f) {
            val dt = minOf(remaining, 1f / 240f)
            strainVelocity += (-380f * motionStrain - 24f * strainVelocity) * dt
            motionStrain = (motionStrain + strainVelocity * dt).coerceIn(-.065f, .065f)
            pressureVelocity += (380f * (pressureTarget - pressureStrain) - 24f * pressureVelocity) * dt
            pressureStrain += pressureVelocity * dt
            strain = motionStrain + pressureStrain
            remaining -= dt
        }
    }
}
