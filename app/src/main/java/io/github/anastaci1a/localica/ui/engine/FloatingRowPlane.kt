package io.github.anastaci1a.localica.ui.engine

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.*
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

internal data class FloatingRowPlacement(val key: Any, val original: () -> Rect?, val drawn: () -> Boolean,
    val projection: (Rect) -> ToastButtonProjection, val sourceOpacity: () -> Float)

/** One measured face per source. An unplaced face keeps measurement, never a second drawing.
 * Collision projection transforms the body and its overflowing field in the same padded draw. */
@Composable internal fun FloatingRowPlane(placements: List<FloatingRowPlacement>, fieldOutset: Float,
    modifier: Modifier = Modifier, measured: (Int, IntSize) -> Unit, content: @Composable (Int) -> Unit) {
    val clock = ownerClock()
    var origin by remember { mutableStateOf(Offset.Zero) }
    Layout(modifier = modifier.onGloballyPositioned { origin = it.positionInRoot() }, content = {
        placements.forEachIndexed { index, placement -> key(placement.key) {
            Box(Modifier.onSizeChanged { measured(index, it) }.drawWithContent {
                val original = placement.original()
                val projection = original?.let(placement.projection) ?: ToastButtonProjection(0f, 0f, false)
                val alpha = (projection.alpha * placement.sourceOpacity()).coerceIn(0f, 1f)
                if (alpha > 0f) translate(top = projection.offsetY) {
                    if (alpha >= 1f) this@drawWithContent.drawContent()
                    else {
                        val bounds = Rect(-fieldOutset, -fieldOutset, size.width + fieldOutset, size.height + fieldOutset)
                        drawContext.canvas.saveLayer(bounds, Paint().apply { this.alpha = alpha })
                        this@drawWithContent.drawContent()
                        drawContext.canvas.restore()
                    }
                }
            }) { content(index) }
        } }
    }) { measurables, constraints ->
        val faces = measurables.map { it.measure(Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            clock.stamp // Placement follows the existing root frame, never a new clock or trajectory.
            faces.forEachIndexed { index, face ->
                val placement = placements[index]
                val original = placement.original()
                if (placement.drawn() && original != null) face.place((original.left - origin.x).roundToInt(), (original.top - origin.y).roundToInt())
            }
        }
    }
}

/** Existing Queue player-opacity projection, shared by its source and hoisted controls. */
internal fun queuePlayerOpacity(progress: Float): Float = ((progress - .4f) / .6f).coerceIn(0f, 1f)

/** Projection of the existing player owner, never another trajectory or admission latch.
 * An offscreen Queue viewport cannot take the seat. Opening and reversal use the identical law. */
internal fun floatingRowSeat(browseBottom: Float, playerBottom: Float, playerProgress: Float,
    queueBottom: Float?): Float {
    val fallback = browseBottom + (playerBottom - browseBottom) * playerProgress.coerceIn(0f, 1f)
    return if (queueBottom != null) minOf(fallback, queueBottom) else fallback
}

/** Geometry is read by layout/draw; only the binary scene admission invalidates composition. */
internal class FloatingRowSeatProjection(private val browse: () -> Float, private val player: () -> Float,
    private val progress: () -> Float, private val queue: () -> Float?, private val queueShown: () -> Boolean,
    private val tick: () -> Unit = {}, private val limit: () -> Float? = { null }) {
    private val admitted = androidx.compose.runtime.derivedStateOf {
        tick()
        queueShown() && queue()?.let { it <= floatingRowSeat(browse(), player(), progress(), null) } == true
    }
    private val acceptsInput = derivedStateOf { progress() >= .99f }
    val queueInteractive: Boolean get() = acceptsInput.value
    val queueAdmitted: Boolean get() = admitted.value
    val bottom: Float get() { tick(); return floatingRowLimitedBottom(floatingRowSeat(browse(), player(), progress(), queue().takeIf { queueShown() }), limit()) }
}

@Composable internal fun rememberFloatingRowSeat(browse: () -> Float, player: () -> Float,
    progress: () -> Float, queue: () -> Float?, queueShown: () -> Boolean, limit: () -> Float? = { null }): FloatingRowSeatProjection {
    val clock = ownerClock()
    val b = rememberUpdatedState(browse); val p = rememberUpdatedState(player)
    val progressNow = rememberUpdatedState(progress); val q = rememberUpdatedState(queue)
    val shown = rememberUpdatedState(queueShown)
    val limitNow = rememberUpdatedState(limit)
    return remember { FloatingRowSeatProjection({ b.value() }, { p.value() }, { progressNow.value() },
        { q.value() }, { shown.value() }, { clock.stamp; Unit }, { limitNow.value() }) }
}

/** Clamp the shared row after admission; a keyboard must not admit an offscreen Queue. */
internal fun floatingRowLimitedBottom(bottom: Float, limit: Float?): Float = if (limit == null) bottom else minOf(bottom, limit)

/** Every original rectangle receives the same scene displacement before coverage and hit testing. */
internal fun floatingRowOriginal(original: Rect, sceneBottom: Float, limit: Float?): Rect =
    original.translate(0f, floatingRowLimitedBottom(sceneBottom, limit) - sceneBottom)

@Composable internal fun rememberFloatingRowLimit(viewport: FloatingRowViewport, gapPx: Float): () -> Float? {
    val gap = rememberUpdatedState(gapPx)
    return remember(viewport) { { viewport.bounds?.bottom?.minus(gap.value) } }
}

internal class FloatingRowViewport {
    var bounds by mutableStateOf<Rect?>(null)
        internal set
}
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun Modifier.floatingRowViewport(viewport: FloatingRowViewport): Modifier =
    windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)).onGloballyPositioned {
    val origin = it.positionInRoot()
    viewport.bounds = Rect(origin.x, origin.y, origin.x + it.size.width, origin.y + it.size.height)
}

/** One source-owned availability projection, including the original native hit transform. */
@Composable internal fun FloatingAvailabilityFace(tag: String, opacity: () -> Float,
    progress: () -> Float, offset: () -> Float, content: @Composable () -> Unit) {
    Box(Modifier.testTag(tag).offset { IntOffset(0, offset().roundToInt()) }.graphicsLayer {
        alpha = opacity()
        val scale = .92f + .08f * progress()
        scaleX = scale; scaleY = scale
    }) { content() }
}

/** Share the Queue source's opacity owner, without a legacy Screens transform at the call site. */
internal fun Modifier.queuePlayerVisibility(progress: () -> Float): Modifier = graphicsLayer {
    alpha = queuePlayerOpacity(progress())
}

/** Preserve a source that still draws behind an expanding opaque surface. This is the exact
 * inverse of that surface's existing contour, applied to body and field together at draw time. */
@Composable internal fun OccludedFloatingPlane(mask: () -> androidx.compose.ui.graphics.Shape,
    occluding: () -> Boolean, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().drawWithContent {
        if (!occluding()) drawContent()
        else {
            val path = androidx.compose.ui.graphics.Path().apply {
                addOutline(mask().createOutline(size, layoutDirection, this@drawWithContent))
            }
            clipPath(path, clipOp = androidx.compose.ui.graphics.ClipOp.Difference) { this@drawWithContent.drawContent() }
        }
    }) { content() }
}
