package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/** Use the reported final IME viewport for both size and anchor, even while foreground
 * measurement still reflects the taller current viewport. */
internal fun searchPanelTarget(origin: Offset, availableWidth: Float, availableHeight: Float,
    width: Float, measuredHeight: Float, margin: Float, ime: Int, imeTarget: Int, safeBottom: Int = 0, opening: Boolean = false): Rect {
    // safeDrawingPadding already consumes the greater of system/cutout and IME.
    val currentConsumed = maxOf(ime, safeBottom)
    val targetConsumed = maxOf(if (opening) maxOf(ime, imeTarget) else imeTarget, safeBottom)
    val targetAvailable = if (ime > 0 || opening) availableHeight - (targetConsumed - currentConsumed).coerceAtLeast(0) else availableHeight
    val height = minOf(measuredHeight, (targetAvailable - margin * 2).coerceAtLeast(1f))
    val top = if (ime > 0 || opening) (targetAvailable - height - margin).coerceAtLeast(margin)
        else ((availableHeight - height) / 2f).coerceAtLeast(margin)
    return Rect(origin + Offset((availableWidth - width) / 2f, top), Size(width, height))
}

/** Test-observer record of where a placed flight began; nothing renders from it. */
internal data class SearchMorphFlight(val from: Rect, val startingProgress: Float, val opening: Boolean)
internal typealias SearchSlotTravel = io.github.anastaci1a.localica.ui.engine.SearchSlotTravel

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

/** Placement instrumentation plus the last placed IME height (the IME-Back hold's input). */
internal class SearchMorphMotion {
    var rendered = Rect.Zero
    var lastIme = 0
    var target = Rect.Zero
    var flight: SearchMorphFlight? = null
    var observer: ((SearchMorphSample) -> Unit)? = null
}

/** The enclosing popup's one dismissal: Hide, input and semantics turn at this event (#960). */
internal val LocalPopupDismiss = staticCompositionLocalOf<(() -> Unit)?> { null }

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
    /** Same rectangle animation owns opening movement and size, before actual placement. */
    val openingOwnedBounds: Rect? = null,
    /** Bound bottom sampled in this placement callback, in both directions. */
    val slotBottom: Float? = null,
)
internal val LocalSearchMorphObserver = staticCompositionLocalOf<((SearchMorphSample) -> Unit)?> { null }

/** Root-window layout evidence; popup callbacks belong to a different traversal. */
internal data class SearchSlotLayoutSample(val fraction: Float, val rowTop: Float)
internal val LocalSearchSlotLayoutObserver = staticCompositionLocalOf<((SearchSlotLayoutSample) -> Unit)?> { null }
