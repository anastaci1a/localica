package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ApproachLayoutModifierNode
import androidx.compose.ui.layout.ApproachMeasureScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.constrain
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Bounds motion for lazily recycled browse content (#635).
 *
 * Like `animateBounds`, this eases a layout between successive lookahead bounds, measured in
 * [lookahead] without the lazy grid's scroll placement. Unlike it, the motion belongs to the bound
 * item, not to the recycled slot: slot reuse forgets the previous item's bounds, so a row that
 * enters through scrolling, a fling or a scrollbar jump is placed at its final slot on its first
 * frame. A null [spec] applies a change in the same frame, without a frame of stale bounds.
 * [modifier] sits between the outer bounds and the content measured at the animated size.
 */
internal fun Modifier.slotBounds(lookahead: LookaheadScope, modifier: Modifier = Modifier,
    spec: () -> FiniteAnimationSpec<Rect>?): Modifier =
    then(SlotBoundsElement(lookahead, spec, content = false)).then(modifier)
        .then(SlotBoundsElement(lookahead, spec, content = true))

private data class SlotBoundsElement(val lookahead: LookaheadScope, val spec: () -> FiniteAnimationSpec<Rect>?,
    val content: Boolean) : ModifierNodeElement<SlotBoundsNode>() {
    override fun create() = SlotBoundsNode(lookahead, spec, content)
    override fun update(node: SlotBoundsNode) {
        node.lookahead = lookahead
        node.spec = spec
        node.content = content
        // Motion disabled midway (reduced motion, reorder, loading) ends at the target now.
        node.settleIfDisabled()
    }
    override fun InspectorInfo.inspectableProperties() { name = "slotBounds" }
}

internal class SlotBoundsNode(var lookahead: LookaheadScope, var spec: () -> FiniteAnimationSpec<Rect>?,
    var content: Boolean) : Modifier.Node(), ApproachLayoutModifierNode {
    private var targetSize: IntSize? = null
    /** Last lookahead bounds in the scope, excluding scroll placement. */
    private var target: Rect? = null
    /** Bounds this node last laid out at: the start of the next intentional change. */
    private var current: Rect? = null
    private var animation: Animatable<Rect, AnimationVector4D>? = null
    private var job: Job? = null
    private var pending = false

    /** True while this node is easing toward a new target or has a change pending. */
    val isPlacementAnimating: Boolean get() = pending || job?.isActive == true

    /** A reused lazy slot binds another item; nothing of the previous item's bounds survives. */
    override fun onReset() {
        job?.cancel()
        job = null
        animation = null
        targetSize = null
        target = null
        current = null
        pending = false
    }

    /** Ends a running or pending motion at its target when the spec no longer allows motion. */
    fun settleIfDisabled(): Boolean {
        if (!isPlacementAnimating || spec() != null) return false
        job?.cancel()
        job = null
        animation = null
        pending = false
        target?.let { current = it }
        return true
    }

    override fun isMeasurementApproachInProgress(lookaheadSize: IntSize): Boolean {
        settleIfDisabled()
        val previous = targetSize
        targetSize = lookaheadSize
        if (previous != null && previous != lookaheadSize && target != null && spec() != null) {
            pending = true
            // Approach measurement observes this value; motion starts from it at placement.
            if (job?.isActive != true) animation = Animatable(current ?: requireNotNull(target), Rect.VectorConverter)
        }
        return isPlacementAnimating
    }

    private fun LayoutCoordinates.inScope(lookaheadPosition: Boolean, scope: LayoutCoordinates): Offset = with(lookahead) {
        if (lookaheadPosition) scope.localLookaheadPositionOf(this@inScope, includeMotionFrameOfReference = false)
        else scope.localPositionOf(this@inScope, Offset.Zero, includeMotionFrameOfReference = false)
    }

    override fun Placeable.PlacementScope.isPlacementApproachInProgress(lookaheadCoordinates: LayoutCoordinates): Boolean {
        val coordinates = coordinates ?: return isPlacementAnimating
        val size = targetSize ?: lookaheadCoordinates.size
        val next = Rect(coordinates.inScope(true, with(lookahead) { lookaheadScopeCoordinates }), size.toSize())
        val previous = target
        target = next
        val changed = previous != null && !previous.roundedEquals(next)
        // A disabled spec must not leave an earlier motion running toward an unchanged target.
        settleIfDisabled()
        pending = false
        if (!changed) {
            if (job?.isActive != true) current = next
            return isPlacementAnimating
        }
        val motion = spec()
        if (motion == null) {
            job?.cancel()
            job = null
            animation = null
            current = next
            return false
        }
        // Continue a running motion; otherwise start where this node was last laid out.
        val from = current ?: requireNotNull(previous)
        val running = animation?.takeIf { job?.isActive == true || it.value == from }
            ?: Animatable(from, Rect.VectorConverter).also { animation = it }
        // Start undispatched so this frame already reports the motion as in progress.
        job = coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            running.animateTo(next, motion)
            current = next
        }
        return isPlacementAnimating
    }

    override fun ApproachMeasureScope.approachMeasure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val size = shown()?.size?.let { IntSize(it.width.roundToInt(), it.height.roundToInt()) } ?: lookaheadSize
        val chosen = if (content) Constraints.fixed(size.width, size.height) else constraints
        val placeable = measurable.measure(chosen)
        val (width, height) = chosen.constrain(size)
        return layout(width, height) {
            val bounds = shown()
            val topLeft = bounds?.topLeft ?: target?.topLeft
            val coordinates = coordinates
            if (bounds != null) current = bounds
            val origin = if (topLeft != null && coordinates != null)
                topLeft - coordinates.inScope(false, with(lookahead) { lookaheadScopeCoordinates }) else Offset.Zero
            placeable.place(origin.x.roundToInt(), origin.y.roundToInt())
        }
    }

    /** The running value, or the start bounds of a change whose motion begins at placement. */
    private fun shown(): Rect? = if (job?.isActive == true || pending) animation?.value else null

    private fun Rect.roundedEquals(other: Rect) = left.roundToInt() == other.left.roundToInt() &&
        top.roundToInt() == other.top.roundToInt() && width.roundToInt() == other.width.roundToInt() &&
        height.roundToInt() == other.height.roundToInt()
}
