package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.spring
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
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

/** An attaching popup can briefly inherit the previous window's Compose IME value.
 * Start from its own focused native window; once acquired, position remains owned
 * through inset disappearance/retargeting until the opening hands off or cancels. */
internal fun searchOpeningOwnsPosition(positioned: Boolean, ime: Int, nativeIme: Int,
    windowFocused: Boolean, focusRequested: Boolean?, fallbackReady: Boolean): Boolean =
    positioned || (ime > 0 && nativeIme > 0 && windowFocused) || focusRequested == false || fallbackReady

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
    internal fun fraction(progress: Float): Float {
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

/** Vacancy follows the existing placement state; this owner has no clock or animation. */
internal class SearchSlotMotion {
    private var generation = 0L
    private var retained by mutableFloatStateOf(0f)
    private var reader by mutableStateOf<(() -> Float)?>(null)
    val fraction: Float get() = (reader?.invoke() ?: retained).coerceIn(0f, 1f)

    fun begin(): Long {
        val current = fraction
        generation++
        reader = null
        retained = current
        return generation
    }
    fun bind(token: Long, value: () -> Float) {
        if (token == generation) reader = value
    }
    /** Native-window replacement retires old writers without inventing another clock. */
    fun renew(): Long = ++generation
    fun settle(token: Long, value: Float) {
        if (token != generation) return
        reader = null
        retained = value.coerceIn(0f, 1f)
    }
}

/** One grid traversal owns its anchor request and spacer measurement together. */
internal data class SearchSlotMeasure(val fraction: Float, val anchorOffset: Int?)

internal class SearchSlotLayout {
    private data class Anchor(val fraction: Float, val offset: Int, val vacancy: Float)
    private class Pass {
        var fraction = 0f
        var anchor: Anchor? = null
    }
    private val lookahead = Pass()
    private val actual = Pass()
    var placedFraction = 0f
        private set

    private fun pass(isLookingAhead: Boolean) = if (isLookingAhead) lookahead else actual

    /** Call before measuring the grid, not from its already-measuring header. */
    fun measure(isLookingAhead: Boolean, fraction: Float, originalOffset: Int, vacancy: Float): SearchSlotMeasure {
        val current = pass(isLookingAhead)
        val captured = fraction.coerceIn(0f, 1f)
        current.fraction = captured
        val anchor = if (originalOffset >= 0 && vacancy > 0f) Anchor(captured, originalOffset, vacancy) else null
        val request = anchor?.offset?.takeIf { anchor != current.anchor }
        current.anchor = anchor
        return SearchSlotMeasure(captured, request)
    }

    /** The spacer observes the original motion for invalidation, then consumes this pass. */
    fun fraction(isLookingAhead: Boolean): Float = pass(isLookingAhead).fraction

    /** Speculative measurement/placement never relabels the actual row witness. */
    fun place(isLookingAhead: Boolean, measure: SearchSlotMeasure) {
        if (!isLookingAhead) placedFraction = measure.fraction
    }
}

/** Rebase on retargeting before changing the destination; no jump or guessed duration. */
internal data class SearchSlotTravel(val fromFraction: Float, val fromBottom: Float, val toBottom: Float) {
    fun fraction(bottom: Float): Float {
        val distance = toBottom - fromBottom
        val traveled = if (kotlin.math.abs(distance) <= .0001f) 1f else ((bottom - fromBottom) / distance).coerceIn(0f, 1f)
        return (fromFraction + (1f - fromFraction) * traveled).coerceIn(0f, 1f)
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
    val nativeIme: Int = ime,
    val windowFocused: Boolean = true,
    /** Same reader used by the underlying list, sampled with this placed geometry. */
    val slotFraction: Float = 0f,
    val slotTravel: SearchSlotTravel? = null,
    val slotReturnFrom: Float = 0f,
)
internal val LocalSearchMorphObserver = staticCompositionLocalOf<((SearchMorphSample) -> Unit)?> { null }

/** Root-window layout evidence; popup callbacks belong to a different traversal. */
internal data class SearchSlotLayoutSample(val fraction: Float, val rowTop: Float)
internal val LocalSearchSlotLayoutObserver = staticCompositionLocalOf<((SearchSlotLayoutSample) -> Unit)?> { null }
