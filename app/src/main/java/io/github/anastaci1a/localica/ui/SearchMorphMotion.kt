package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.spring
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp

internal val SearchRectConverter = TwoWayConverter<Rect, AnimationVector4D>(
    { AnimationVector4D(it.left, it.top, it.right, it.bottom) },
    { Rect(it.v1, it.v2, it.v3, it.v4) },
)

internal fun searchPlacementSpring(speed: Float) = glassMorphSpring(speed).let {
    spring(dampingRatio = it.dampingRatio, stiffness = it.stiffness,
        visibilityThreshold = Rect(.5f, .5f, .5f, .5f))
}

internal fun searchBottomSpring(speed: Float) = glassMorphSpring(speed).let {
    spring(dampingRatio = it.dampingRatio, stiffness = it.stiffness, visibilityThreshold = .5f)
}

/** Use the reported final IME viewport for both size and anchor, even while foreground
 * measurement still reflects the taller current viewport. */
internal fun searchPanelTarget(origin: Offset, availableWidth: Float, availableHeight: Float,
    width: Float, measuredHeight: Float, margin: Float, ime: Int, imeTarget: Int, safeBottom: Int = 0): Rect {
    // safeDrawingPadding already consumes the greater of system/cutout and IME.
    val currentConsumed = maxOf(ime, safeBottom)
    val targetConsumed = maxOf(imeTarget, safeBottom)
    val targetAvailable = if (ime > 0) availableHeight - (targetConsumed - currentConsumed).coerceAtLeast(0) else availableHeight
    val height = minOf(measuredHeight, (targetAvailable - margin * 2).coerceAtLeast(1f))
    val top = if (ime > 0) (targetAvailable - height - margin).coerceAtLeast(margin)
        else ((availableHeight - height) / 2f).coerceAtLeast(margin)
    return Rect(origin + Offset((availableWidth - width) / 2f, top), Size(width, height))
}

/** Complete window-space geometry; animated IME padding cannot move a captured flight. */
internal data class SearchMorphFlight(val from: Rect, val startingProgress: Float, val opening: Boolean) {
    private fun fraction(progress: Float): Float {
        val remaining = if (opening) 1f - startingProgress else startingProgress
        val traveled = if (opening) progress - startingProgress else startingProgress - progress
        return if (remaining <= .0001f) 1f else (traveled / remaining).coerceIn(0f, 1f)
    }

    fun rectangle(progress: Float, destination: Rect): Rect = lerp(from, destination, fraction(progress))

    /** Shape growth and keyboard travel have independent owners. Before travel, grow
     * down from the pill; after travel starts, the moving bottom supports upward growth. */
    fun openingRectangle(progress: Float, destination: Rect, placementBottom: Float?,
        maxHeight: Float = Float.POSITIVE_INFINITY): Rect {
        val shaped = rectangle(progress, destination)
        val height = shaped.height.coerceAtMost(maxHeight)
        val top = placementBottom?.minus(height) ?: from.top
        return Rect(shaped.left, top, shaped.right, top + height)
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
    val placementBottom: Float? = null,
    val openingShapeDestination: Rect? = null,
    val openingHeightLimit: Float = Float.POSITIVE_INFINITY,
    /** Exact draw-gate opacity sampled at placement, not a rendered-pixel observation. */
    val surfaceOpacity: Float = 1f,
)
internal val LocalSearchMorphObserver = staticCompositionLocalOf<((SearchMorphSample) -> Unit)?> { null }
