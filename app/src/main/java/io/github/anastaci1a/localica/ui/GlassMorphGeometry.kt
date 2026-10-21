package io.github.anastaci1a.localica.ui

/** Quiet controls finish all shape changes together before their temporary lens fades. */
internal fun glassMorphGeometryProgress(progress: Float, quiet: Boolean): Float {
    val p = progress.coerceIn(0f, 1f)
    return if (quiet) ((p - .14f) / .86f).coerceIn(0f, 1f) else p
}
