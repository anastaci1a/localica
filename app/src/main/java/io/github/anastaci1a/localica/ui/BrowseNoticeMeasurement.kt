package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.view.View
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.remeasureSync
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/** A receipt from the actual notice node; query/return intent does not own its lifetime. */
internal class BrowseNoticeMeasurement {
    private data class Receipt(val node: Any, val content: Any, val width: Int,
        val density: Float, val fontScale: Float, val direction: LayoutDirection, val height: Int,
        val lookahead: Boolean) {
        fun sameKey(other: Receipt) = content === other.content && width == other.width &&
            density == other.density && fontScale == other.fontScale && direction == other.direction
    }
    private var receipt by mutableStateOf<Receipt?>(null)
    private var attached: Any? = null

    val width: Int? get() = receipt?.width
    /** Evidence only: which pass published the current receipt and for which notice identity. */
    val lookahead: Boolean? get() = receipt?.lookahead
    val contentIdentity: Int? get() = receipt?.let { System.identityHashCode(it.content) }
    fun hasReceipt(node: Any, content: Any): Boolean = receipt?.let {
        it.node === node && it.node === attached && it.content === content
    } == true

    fun height(content: Any, width: Int, density: Density, direction: LayoutDirection): Int? =
        receipt?.takeIf { it.content === content && it.width == width &&
            it.density == density.density && it.fontScale == density.fontScale && it.direction == direction }?.height

    fun attach(node: Any) { attached = node }
    fun detach(node: Any, cached: Boolean = false) {
        if (attached === node) {
            attached = null
            if (!cached) receipt = null
        }
    }
    fun invalidate(node: Any) { if (attached === node) receipt = null }
    /**
     * Publishes one pass of the attached node. The approach pass always wins. A lookahead
     * pass only fills a missing or differently keyed receipt: when the lazy grid canonicalizes
     * a zero-height notice away from its visible lines, that lookahead result is the only
     * geometry it ever used, and no approach pass follows for the same node and frame.
     */
    fun measured(node: Any, content: Any, width: Int, height: Int, density: Density, direction: LayoutDirection,
        lookahead: Boolean = false) {
        if (attached !== node) return
        val next = Receipt(node, content, width, density.density, density.fontScale, direction, height, lookahead)
        val current = receipt
        if (lookahead && current != null && !current.lookahead && current.node === node && current.sameKey(next)) return
        receipt = next
    }
}

internal fun Modifier.measureBrowseNotice(state: BrowseNoticeMeasurement, content: Any): Modifier =
    then(NoticeMeasureElement(state, content))

private data class NoticeMeasureElement(val state: BrowseNoticeMeasurement, val content: Any) :
    ModifierNodeElement<NoticeMeasureNode>() {
    override fun create() = NoticeMeasureNode(state, content)
    override fun update(node: NoticeMeasureNode) = node.update(state, content)
    override fun InspectorInfo.inspectableProperties() { name = "measureBrowseNotice" }
}

private class NoticeMeasureNode(var state: BrowseNoticeMeasurement, var content: Any) :
    Modifier.Node(), LayoutModifierNode, CompositionLocalConsumerModifierNode {
    private val token = Any()
    private var cachedReset = false
    private var measured = false
    private var measuring = false
    private var postedHost: View? = null
    private var pending: Runnable? = null
    private fun cancelRemeasure() {
        pending?.let { postedHost?.removeCallbacks(it) }
        pending = null
        postedHost = null
    }
    override fun onAttach() { cachedReset = false; state.attach(token) }
    override fun onDetach() {
        cancelRemeasure()
        state.detach(token, cached = cachedReset)
        cachedReset = false
        measured = false
    }
    override fun onReset() {
        // Pinned LazyLayout deactivation resets, then detaches a cached item. Its
        // immutable receipt still describes this logical notice/content/constraints;
        // detach revokes the writer without inventing or remeasuring geometry.
        cancelRemeasure()
        cachedReset = true
        state.detach(token, cached = true)
        measured = false
    }

    fun update(next: BrowseNoticeMeasurement, identity: Any) {
        if (next === state && identity === content) return
        if (next !== state) {
            state.detach(token)
            state = next
            if (isAttached) state.attach(token)
        }
        content = identity
        state.invalidate(token)
        // A cached item can be just outside the visible lines. Invalidate its actual
        // node after child updates apply, instead of waiting for another scroll.
        cancelRemeasure()
        if (isAttached && measured) sideEffect {
            if (isAttached && content === identity && state === next) {
                val host = currentValueOf(LocalView)
                val request = object : Runnable {
                    override fun run() {
                        if (pending !== this) return
                        pending = null
                        postedHost = null
                        if (isAttached && measured && !measuring && content === identity && state === next &&
                            host.isAttachedToWindow && currentValueOf(LocalView) === host &&
                            !state.hasReceipt(token, identity)) remeasureSync()
                    }
                }
                postedHost = host
                pending = request
                // One cancellable native geometry invalidation, outside subcomposition's
                // measure/apply stack. No timestamp, animation clock or repeated loop.
                host.postOnAnimation(request)
            }
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        measuring = true
        try {
            val child = measurable.measure(constraints)
            // The grid measures its items with an unbounded main axis. A bounded height is
            // some other owner's constraint, not notice geometry: publish nothing for it.
            if (constraints.maxHeight == Constraints.Infinity) {
                measured = true
                state.measured(token, content, child.width, child.height, this, layoutDirection, lookahead = isLookingAhead)
            } else state.invalidate(token)
            return layout(child.width, child.height) { child.placeRelative(0, 0) }
        } finally { measuring = false }
    }
}
