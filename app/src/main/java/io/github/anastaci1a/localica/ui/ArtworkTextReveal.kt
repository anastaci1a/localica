package io.github.anastaci1a.localica.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import io.github.anastaci1a.localica.ui.components.ArtworkReveal
import io.github.anastaci1a.localica.ui.components.NoArtworkReveal

/**
 * One artwork reveal drives its item's text (#526) and row action (#588). While the art loads, the text waits behind
 * gray skeleton bars. When the art reveals, the text crossfades in with the same reveal value
 * and finishes with it. Only art known to exist (a stored colour) closes the gate; cached,
 * missing and not-yet-indexed art show text at once, so a slow or failing read never hides it. Once the text has been
 * shown, the gate stays open for that item, so pinches and cache refreshes never replay it.
 * Animation frames invalidate drawing; only the menu's visibility boundary changes its input.
 */
@Stable
internal class ArtworkTextReveal(private val sourceOpacity: (() -> Float)? = null) {
    internal var reveal: ArtworkReveal = NoArtworkReveal
    private val gate = mutableStateOf(false)
    private var released = false
    private val menuGate = mutableStateOf(false)
    private var menuReleased = false

    private var started = false

    /** Called from the artwork's composition, before the text draws in the same frame. Only the
     * item's first composition can close the gate, so visible text never disappears again. */
    internal fun hold(waiting: Boolean) {
        if (released) return
        if (!started) { started = true; gate.value = waiting }
        if (!waiting) release()
    }
    internal fun release() {
        released = true
        if (gate.value) gate.value = false
    }
    // Unlike text, the action waits for unindexed artwork too. Both gates still read
    // the artwork's single reveal, and neither can close again after release.
    internal fun holdMenu(waiting: Boolean) {
        if (menuReleased) return
        if (waiting) menuGate.value = true else releaseMenu()
    }
    internal fun releaseMenu() {
        menuReleased = true
        if (menuGate.value) menuGate.value = false
    }
    val waiting: Boolean get() = gate.value
    // The single read of the artwork's reveal, shared by the text and action gates.
    private val revealAlpha: Float get() = reveal.alpha()
    val textAlpha: Float get() = if (!gate.value) 1f else sourceOpacity?.invoke() ?: revealAlpha
    internal fun menuAlpha(artworkVisibility: Float): Float {
        val alpha = if (!menuGate.value) 1f else revealAlpha
        // The existing artwork-slot transition also owns the no-art endpoint. This
        // keeps the action continuous when a pinch removes or introduces that slot.
        return (1f - artworkVisibility) + artworkVisibility * alpha
    }
    /** Whether [menuAlpha] is above zero, from the gate and the reveal's intent and rest rather than its value. */
    internal fun menuShown(artworkVisibility: Float): Boolean = artworkVisibility < 1f || !menuGate.value || reveal.shown()
}

/** Gray text skeleton under the waiting text, fading as the text fades in. The skeleton keeps
 * the neutral loading material and the shared sweep clock, like the catalog's text shapes. */
@Composable
internal fun Modifier.artworkTextReveal(reveal: ArtworkTextReveal?, layout: () -> TextLayoutResult?, cornerPx: () -> Float,
    skeleton: Boolean = true): Modifier {
    if (reveal == null) return this
    val base = LocalLoadingMaterialColor.current ?: MaterialTheme.colorScheme.surfaceContainer
    val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = .065f)
    val shimmer = if (LocalReducedMotion.current) null else LocalLoadingShimmer.current
    val opacity = LocalLoadingMaterialOpacity.current.coerceIn(0f, 1f)
    return drawWithCache {
        // Layout changes rebuild geometry in the draw phase. Reveal and shimmer frames
        // reuse these line boxes without recomposing or remeasuring the paragraph.
        val lines = layout()?.let { text ->
            // The row's Box aligns its paragraph to Start. A short RTL title can be
            // narrower than that box, so retain the child's placement as well as its metrics.
            val x = if (layoutDirection == LayoutDirection.Rtl) size.width - text.size.width else 0f
            List(text.lineCount) { line ->
                Rect(x + text.getLineLeft(line), text.getLineTop(line), x + text.getLineRight(line), text.getLineBottom(line))
            }
        }.orEmpty()
        val sweep = Brush.linearGradient(listOf(Color.Transparent, highlight, Color.Transparent),
            start = Offset(-size.width * .6f, 0f), end = Offset(size.width * .6f, size.height))
        onDrawBehind {
            if (!skeleton || !reveal.waiting) return@onDrawBehind
            val alpha = (1f - reveal.textAlpha) * opacity
            // A fully revealed skeleton draws at zero alpha until the reveal releases the text.
            if (opacity <= 0f || size.width <= 0f || size.height <= 0f) return@onDrawBehind
            for (bar in lines) {
                if (bar.width <= 0f || bar.height <= 0f) continue
                val radius = CornerRadius(cornerPx().coerceAtMost(bar.height / 2f))
                drawRoundRect(base, topLeft = bar.topLeft, size = bar.size, cornerRadius = radius, alpha = alpha)
                shimmer?.let {
                    val x = size.width * it.value
                    translate(left = x) {
                        drawRoundRect(sweep, topLeft = Offset(bar.left - x, bar.top), size = bar.size, cornerRadius = radius, alpha = alpha)
                    }
                }
            }
        }
    }.graphicsLayer { alpha = reveal.textAlpha }
}
