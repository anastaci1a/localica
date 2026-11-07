package io.github.anastaci1a.localica.ui.engine

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.*
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.*
import kotlin.math.roundToInt

/** U04.02a: a face that places its children only while [visible]; otherwise it draws its last recording at [alpha] (0..1), with input and
 * semantics revoked. [inset] pads the recording for an external shadow, [retainSize] keeps the last visible footprint and [scale] is the
 * size at alpha 0 (presence). Moved from GlassMorphContent's RetainedGlassFace; SurfaceNode.retained replaces it in part B. */
@Composable fun RetainedFace(visible: Boolean, alpha: () -> Float, modifier: Modifier = Modifier, inset: Dp = 0.dp, retainSize: Boolean = false,
    scale: Float = 1f, onMeasured: (IntSize) -> Unit = {}, content: @Composable () -> Unit) {
    val layer = rememberGraphicsLayer()
    val measurement = remember { RetainedMeasurement() }
    Layout(modifier = modifier.then(if (visible) Modifier else Modifier.clearAndSetSemantics { })
        .drawWithContent {
            val pad = inset.roundToPx()
            if (visible) layer.record(size = IntSize(size.width.toInt() + pad * 2, size.height.toInt() + pad * 2)) {
                translate(pad.toFloat(), pad.toFloat()) { this@drawWithContent.drawContent() }
            }
            val opacity = alpha().coerceIn(0f, 1f)
            if (opacity > 0f && layer.size.width > 0) {
                layer.alpha = opacity
                if (scale != 1f) { layer.scaleX = scale + (1f - scale) * opacity; layer.scaleY = layer.scaleX }
                translate(-pad.toFloat(), -pad.toFloat()) { drawLayer(layer) }
            }
        }, content = { Box { content() } }) { children, constraints ->
        val child = children.single().measure(constraints)
        if (visible || measurement.size == IntSize.Zero) measurement.size = IntSize(child.width, child.height)
        val size = if (retainSize && !visible) measurement.size else IntSize(child.width, child.height)
        onMeasured(size)
        layout(size.width, size.height) { if (visible) child.place(0, 0) }
    }
}

private class RetainedMeasurement { var size = IntSize.Zero }

/** A face's frame centred on its origin at [scale], for an [ExpansionBasis] between centred faces. */
internal fun centeredFrame(size: IntSize, scale: Float = 1f) = Size(size.width * scale, size.height * scale).let { Rect(Offset(-it.width / 2, -it.height / 2), it) }
/** D09: a shared element's frame at [p] eases every edge linearly between its compact and expanded slots. */
internal fun sharedFrame(from: Rect, to: Rect, p: Float) = lerp(from, to, p)
private fun ease(a: IntSize, b: IntSize, p: Float) = IntSize((a.width + (b.width - a.width) * p).roundToInt(), (a.height + (b.height - a.height) * p).roundToInt())

/** D09 shared-element bookkeeping of one two-face morph: per key, both faces' slot frames and endpoint sizes, their ink, and the one
 * instance's recording. Slots write only plain fields in layout and draw, so nothing here invalidates. */
@Stable class MorphSlots internal constructor(private val phase: MotionValue) {
    internal class Slot { val frames = arrayOfNulls<LayoutCoordinates>(2); val sizes = arrayOf(IntSize.Zero, IntSize.Zero)
        val ink = arrayOf(Color.Unspecified, Color.Unspecified); var layer: GraphicsLayer? = null; var drawn = IntSize.Zero }
    internal val slots = mutableMapOf<Any, Slot>()
    private var root: LayoutCoordinates? = null
    /** 0 (compact) … 1 (expanded) on the morph's one clock; sample it only in layout or draw. */
    val progress: () -> Float = { phase.value().coerceIn(0f, 1f) }

    /** The two faces: the destination places its children, the other is its last recording fading on the same phase. The root eases between
     * the faces' sizes. The other face measures first, so the destination's shared slots ease toward a current endpoint. Shared instances
     * draw above both faces at their eased frames, tinted between the faces' inks. */
    @Composable fun Faces(expanded: Boolean, onMeasured: (IntSize, IntSize) -> Unit, modifier: Modifier, compact: @Composable () -> Unit,
        content: @Composable () -> Unit) {
        Layout(modifier = modifier.onPlaced { root = it }.drawWithContent { drawContent(); drawShared() }, content = {
            RetainedFace(!expanded, { 1f - progress() }, content = compact)
            RetainedFace(expanded, progress, content = content)
        }) { children, constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val faces = arrayOfNulls<Placeable>(2)
            (if (expanded) listOf(0, 1) else listOf(1, 0)).forEach { faces[it] = children[it].measure(loose) }
            val small = IntSize(faces[0]!!.width, faces[0]!!.height); val large = IntSize(faces[1]!!.width, faces[1]!!.height)
            onMeasured(small, large)
            val size = ease(small, large, progress())
            layout(size.width, size.height) { faces.forEach { it!!.placeRelative((size.width - it.width) / 2, (size.height - it.height) / 2) } }
        }
    }

    /** Face [face]'s slot for shared [key]. It takes the size of the measure-only [copy] (never placed, drawn or in semantics). In the
     * destination face it also places the one [instance], measured at the eased size; its drawing is recorded here and drawn by [Faces]. */
    @Composable fun Slot(key: Any, face: Int, destination: Boolean, ink: Color, modifier: Modifier, copy: @Composable () -> Unit,
        instance: @Composable () -> Unit) {
        val slot = slots.getOrPut(key) { Slot() }
        val layer = rememberGraphicsLayer()
        SideEffect { slot.ink[face] = ink }
        Layout(modifier = modifier.onPlaced { slot.frames[face] = it }.drawWithContent {
            if (destination) { layer.record(slot.drawn) { this@drawWithContent.drawContent() }; slot.layer = layer }
        }, content = {
            Box(Modifier.clearAndSetSemantics { }) { copy() }
            if (destination) instance()
        }) { children, constraints ->
            val own = children[0].measure(constraints)
            slot.sizes[face] = IntSize(own.width, own.height)
            val placed = children.getOrNull(1)?.let { slot.drawn = ease(slot.sizes[0], slot.sizes[1], progress()); it.measure(Constraints.fixed(slot.drawn.width, slot.drawn.height)) }
            layout(own.width, own.height) { placed?.place(0, 0) }
        }
    }

    private fun DrawScope.drawShared() {
        val root = root?.takeIf { it.isAttached } ?: return; val p = progress()
        for (slot in slots.values) {
            val layer = slot.layer ?: continue
            val frames = slot.frames.map { it?.takeIf { c -> c.isAttached }?.let { c -> Rect(root.localPositionOf(c, Offset.Zero), c.size.toSize()) } }
            val from = frames[0] ?: frames[1] ?: continue; val to = frames[1] ?: from
            val ink = slot.ink.takeIf { it[0] != it[1] && it.all { c -> c.isSpecified } }?.let { lerp(it[0], it[1], p) }
            layer.colorFilter = ink?.let { ColorFilter.tint(it) }
            val frame = sharedFrame(from, to, p); translate(frame.left, frame.top) { drawLayer(layer) }
        }
    }
}

@Composable fun rememberMorphSlots(phase: MotionValue) = remember(phase) { MorphSlots(phase) }
