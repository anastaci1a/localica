package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue

internal const val BrowseViewFadeMillis = 120
internal const val BrowseViewTransitionFraction = .45f

/** Longer journeys disappear earlier; the reverse arrival uses the identical window. */
internal fun browsePinchFadeWindow(distance: Float, viewport: Float): Float =
    BrowseViewTransitionFraction / (1f + distance.coerceAtLeast(0f) / viewport.coerceAtLeast(1f))

/** Departures stop at their fade-end position and size, without accelerating either. */
internal fun browsePinchGeometryProgress(departing: Boolean, progress: Float,
    fadeWindow: Float = BrowseViewTransitionFraction): Float =
    progress.coerceIn(0f, if (departing) fadeWindow.coerceIn(0f, 1f) else 1f)

/** Visibility finishes before live geometry; departures share its cutoff and reversibility. */
internal fun browseViewVisibility(source: Boolean, target: Boolean, progress: Float,
    fadeWindow: Float = BrowseViewTransitionFraction): Float {
    if (source == target) return if (target) 1f else 0f
    // Use the same spatial coordinate in both directions. An arrival retraces a
    // departure, including its fade window, instead of fading near the other end.
    val position = if (target) progress else 1f - progress
    val window = fadeWindow.coerceIn(.001f, 1f)
    val p = ((position - (1f - window)) / window).coerceIn(0f, 1f)
    val eased = p * p * (3f - 2f * p)
    return eased
}

@Composable
internal fun browseViewAlpha(visible: Boolean, source: Boolean?, progress: Float, reducedMotion: Boolean): Float {
    val target = if (visible) 1f else 0f
    val duration = if (reducedMotion || source != null) 0 else
        (BrowseViewFadeMillis / LocalMotionSpeed.current).toInt()
    val animated by animateFloatAsState(target, tween(duration), label = "Browse visibility")
    return when {
        reducedMotion -> target
        source != null -> browseViewVisibility(source, visible, progress)
        else -> animated
    }
}
