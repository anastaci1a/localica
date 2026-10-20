package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.spring
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp

internal val SearchRectConverter = TwoWayConverter<Rect, AnimationVector4D>(
    { AnimationVector4D(it.left, it.top, it.right, it.bottom) },
    { Rect(it.v1, it.v2, it.v3, it.v4) },
)

internal fun searchPlacementSpring(speed: Float) = glassMorphSpring(speed).let {
    spring(dampingRatio = it.dampingRatio, stiffness = it.stiffness,
        visibilityThreshold = Rect(.5f, .5f, .5f, .5f))
}

/** Complete window-space geometry; animated IME padding cannot move a captured flight. */
internal data class SearchMorphFlight(val from: Rect, val startingProgress: Float, val opening: Boolean) {
    fun rectangle(progress: Float, destination: Rect): Rect {
        val remaining = if (opening) 1f - startingProgress else startingProgress
        val traveled = if (opening) progress - startingProgress else startingProgress - progress
        val fraction = if (remaining <= .0001f) 1f else (traveled / remaining).coerceIn(0f, 1f)
        return lerp(from, destination, fraction)
    }
}

internal class SearchMorphMotion {
    var rendered = Rect.Zero
    var lastIme = 0
    var observer: ((SearchMorphSample) -> Unit)? = null
}

/** The editor reports after its window-owned focus/show (or result restoration) request. */
internal val LocalSearchOpeningReady = staticCompositionLocalOf<((Boolean) -> Unit)?> { null }
internal val LocalSearchReturnStart = staticCompositionLocalOf<(() -> Unit)?> { null }

/** Observe actual placed frames without sampling a later animation-clock value. */
internal data class SearchMorphSample(
    val progress: Float,
    val opening: Boolean,
    val bounds: Rect,
    val flight: SearchMorphFlight?,
    val destination: Rect,
    val ime: Int,
    val imeSource: Int,
    val imeTarget: Int,
)
internal val LocalSearchMorphObserver = staticCompositionLocalOf<((SearchMorphSample) -> Unit)?> { null }
