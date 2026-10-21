package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue

internal const val BrowseViewFadeMillis = 120

/** Visibility finishes before geometry, but remains reversible with the finger. */
internal fun browseViewVisibility(source: Boolean, target: Boolean, progress: Float): Float {
    if (source == target) return if (target) 1f else 0f
    val p = (progress / .45f).coerceIn(0f, 1f)
    val eased = p * p * (3f - 2f * p)
    return if (target) eased else 1f - eased
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
