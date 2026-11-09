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

/** A face's frame centred on its origin at [scale], for an [ExpansionBasis] between centred faces. */
internal fun centeredFrame(size: IntSize, scale: Float = 1f) = Size(size.width * scale, size.height * scale).let { Rect(Offset(-it.width / 2, -it.height / 2), it) }
/** D09: a shared element's frame at [p] eases every edge linearly between its compact and expanded slots. */
internal fun sharedFrame(from: Rect, to: Rect, p: Float) = lerp(from, to, p)
private fun ease(a: IntSize, b: IntSize, p: Float) = IntSize((a.width + (b.width - a.width) * p).roundToInt(), (a.height + (b.height - a.height) * p).roundToInt())

/** D09 shared-element bookkeeping of one two-face morph: per key, both faces' slot frames and endpoint sizes, their ink, and the one
 * instance's recording. Slots write only plain fields in layout and draw, so nothing here invalidates. */
@Stable class MorphSlots internal constructor(private val expansion: ExpansionView,private val bounds: ()->MotionRectView?) {
    private val phase get()=expansion.phase
    internal var expandedRest by mutableStateOf(false)
        private set
    internal fun settled(owner: ExpandCollapse?) { expandedRest = owner?.expanded == true && owner.retirementReady }
    internal class Slot { val frames = arrayOfNulls<LayoutCoordinates>(2); val views = arrayOfNulls<android.view.View>(2); val screenFrames = arrayOfNulls<Rect>(2); val sizes = arrayOf(IntSize.Zero, IntSize.Zero)
        val ink = arrayOf(Color.Unspecified, Color.Unspecified); var layer: GraphicsLayer? = null; var drawn = IntSize.Zero }
    internal val slots = mutableMapOf<Any, Slot>()
    private var root: LayoutCoordinates? = null
    private var destination: LayoutCoordinates? = null
    private var destinationOrigin = Offset.Zero
    internal fun destinationLayout(coordinates: LayoutCoordinates?) {
        destination = coordinates
        if (coordinates == null) slots.values.forEach { it.screenFrames[1] = null }
    }
    internal fun destinationOrigin(origin: Offset) { destinationOrigin = origin }
    /** 0 (compact) … 1 (expanded) on the morph's one clock; sample it only in layout or draw. */
    val progress: () -> Float = { phase.value().coerceIn(0f, 1f) }

    /** The two faces: the destination places its children, the other is its last recording fading on the same phase. The root eases between
     * the faces' sizes. The other face measures first, so the destination's shared slots ease toward a current endpoint. Shared instances
     * draw above both faces at their eased frames, tinted between the faces' inks. */
    @Composable fun Faces(expanded: Boolean, onMeasured: (IntSize, IntSize) -> Unit, modifier: Modifier, compact: @Composable () -> Unit,
        content: @Composable () -> Unit) {
        Layout(modifier = modifier.onPlaced { root = it }.drawWithContent { drawContent(); drawShared() }, content = {
            CompositorFace(!expanded, expansion, bounds, Curve.linear(1f,0f), content = compact)
            CompositorFace(expanded, expansion, bounds, Curve.linear(0f,1f), content = content)
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
        val view = androidx.compose.ui.platform.LocalView.current
        val coordinates = remember(slot, face) { arrayOfNulls<LayoutCoordinates>(1) }
        SideEffect { slot.ink[face] = ink }
        DisposableEffect(slot, face, view, layer) { onDispose {
            if (slot.frames[face] === coordinates[0]) { slot.frames[face] = null; slot.views[face] = null }
            if (slot.layer === layer) slot.layer = null
        } }
        Layout(modifier = modifier.onPlaced { coordinates[0] = it; slot.frames[face] = it; slot.views[face] = view }.drawWithContent {
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
        val root = root?.takeIf { it.isAttached } ?: return
        drawShared { slot, face -> slot.frames[face]?.takeIf { it.isAttached }?.let { c ->
            Rect(root.localPositionOf(c, Offset.Zero), c.size.toSize())
        } }
    }

    /** Native adapters supply actual fixed foreground layout plus its measured final envelope.
     * Never interpolate toward the already-scaled current popup rectangle. */
    internal fun drawExternal(scope: DrawScope, coordinates: LayoutCoordinates, view: android.view.View) = with(scope) {
        if (!coordinates.isAttached) return@with
        val origin = screenPosition(coordinates, view)
        drawShared { slot, face ->
            val child = slot.frames[face]?.takeIf { it.isAttached }
            val frame = if (child == null) null else if (face == 1) {
                destination?.takeIf { it.isAttached && it.findRootCoordinates() === child.findRootCoordinates() }?.let { foreground ->
                    Rect(destinationOrigin + foreground.localPositionOf(child, Offset.Zero), child.size.toSize())
                }
            } else slot.views[face]?.let { sourceView -> Rect(screenPosition(child, sourceView), child.size.toSize()) }
            if (frame != null) slot.screenFrames[face] = frame
            (frame ?: slot.screenFrames[face])?.translate(-origin)
        }
    }

    @Composable internal fun externalHost(visible: () -> Boolean): Modifier {
        val view = androidx.compose.ui.platform.LocalView.current
        val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
        return Modifier.onPlaced { coordinates[0] = it }.drawWithContent {
            drawContent()
            if (visible()) coordinates[0]?.let { drawExternal(this, it, view) }
        }
    }

    private fun screenPosition(coordinates: LayoutCoordinates, view: android.view.View): Offset {
        val origin = IntArray(2).also(view::getLocationOnScreen)
        return coordinates.positionInRoot() + Offset(origin[0].toFloat(), origin[1].toFloat())
    }

    private fun DrawScope.drawShared(frameOf: (Slot, Int) -> Rect?) {
        val p = progress()
        for (slot in slots.values) {
            val layer = slot.layer ?: continue
            val frames = (0..1).map { frameOf(slot, it) }
            val from = frames[0] ?: frames[1] ?: continue; val to = frames[1] ?: from
            val ink = slot.ink.takeIf { it[0] != it[1] && it.all { c -> c.isSpecified } }?.let { lerp(it[0], it[1], p) }
            layer.colorFilter = ink?.let { ColorFilter.tint(it) }
            val frame = sharedFrame(from, to, p); translate(frame.left, frame.top) { drawLayer(layer) }
        }
    }
}

@Composable fun rememberMorphSlots(expansion: ExpansionView,bounds: ()->MotionRectView?): MorphSlots {
    val latest by rememberUpdatedState(bounds)
    val slots = remember(expansion) { MorphSlots(expansion) { latest() } }
    DisposableEffect(slots, expansion) {
        val owner = expansion as? ExpandCollapse
        val lifecycle: (Boolean) -> Unit = { slots.settled(owner) }
        slots.settled(owner)
        owner?.lifecycle?.add(lifecycle)
        onDispose { owner?.lifecycle?.remove(lifecycle) }
    }
    return slots
}
