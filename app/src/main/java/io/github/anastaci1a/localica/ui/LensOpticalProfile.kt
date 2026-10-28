package io.github.anastaci1a.localica.ui

import kotlin.math.sqrt

/** Immutable surface-wide optical coefficients shared by shader and mesh adapters.
 * Shape/field, sampling registration and renderer lifetime remain adapter-owned.
 * Prepare once per optical-state change; never allocate this per mesh vertex. */
internal class LensOpticalProfile private constructor(val span: Float, val crown: Float,
    val shoulder: Float, val baseThickness: Float) {
    companion object {
        fun create(span: Float, depth: Float, flattening: Float = 0f): LensOpticalProfile {
            val requested = depth.coerceIn(.05f, 4f)
            val opticalDepth = requested / sqrt(1f + requested * requested / 4f)
            return LensOpticalProfile(span, span * .62f * opticalDepth,
                .4f + .06f * flattening.coerceIn(0f, 1f), span * .32f * opticalDepth)
        }
    }
}
