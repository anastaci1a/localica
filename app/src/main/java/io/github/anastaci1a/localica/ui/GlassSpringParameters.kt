package io.github.anastaci1a.localica.ui

/** Shared spring parameters; each owner retains its integrator and contact policy. */
internal object GlassSpringParameters {
    const val STIFFNESS: Float = 380f
    const val DEFAULT_DAMPING: Float = 8f
    const val MIN_DAMPING: Float = 2f
    const val MAX_DAMPING: Float = 40f
    const val SETTLED_DISPLACEMENT: Float = .0003f
    const val SETTLED_VELOCITY: Float = .003f
}
