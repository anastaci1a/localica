package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp

/** A search flight stays in window coordinates while the IME changes its viewport. */
internal data class SearchMorphFlight(
    val from: Rect,
    val startingProgress: Float,
    val opening: Boolean,
) {
    fun rectangle(progress: Float, destination: Rect): Rect {
        val remaining = if (opening) 1f - startingProgress else startingProgress
        val traveled = if (opening) progress - startingProgress else startingProgress - progress
        val fraction = if (remaining <= .0001f) 1f else (traveled / remaining).coerceIn(0f, 1f)
        return lerp(from, destination, fraction)
    }
}
