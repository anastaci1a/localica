package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

internal const val DropGapKey = "localica-drop-gap"

@Stable
internal class KeyedDrag<T : Any, P>(val original: List<T>, val moving: List<T>,
    val keyOf: (T) -> String,
    val initialBounds: Map<String, Rect>, val grab: Offset, point: Offset, val cardSize: androidx.compose.ui.geometry.Size, val presentation: P) {
    val keys = moving.map { keyOf(it) }.toSet()
    val remaining = original.filterNot { keyOf(it) in keys }
    val remainingPositions = remaining.withIndex().associate { keyOf(it.value) to it.index }
    var pointer by mutableStateOf(point)
    var insertion by mutableIntStateOf(original.indexOfFirst { keyOf(it) in keys }.coerceAtLeast(0))
    var overCancel by mutableStateOf(false)
    var dropping by mutableStateOf(false)
    var dropItems by mutableStateOf<List<T>?>(null)
    var destinations by mutableStateOf<Map<String, Rect>?>(null)
    var returnFrom by mutableStateOf<Map<String, Rect>?>(null)
    val landing = Animatable(0f)
    val handoff = Animatable(0f)
    fun ordered() = remaining.take(insertion) + moving + remaining.drop(insertion)
}

/** Shared transaction for stable, unique String keys; null rendered slots are the insertion gap.
 * Presentation is captured once at lift; adapters own activation and content rendering. */
@Stable
internal abstract class KeyedDragState<T : Any, P>(val grid: LazyGridState, private val scope: CoroutineScope,
    val keyOf: (T) -> String, private val optimisticCommit: Boolean = true) {
    var items: List<T> = emptyList()
    protected abstract fun capturePresentation(): P
    var viewportCoordinates: LayoutCoordinates? = null
    val itemCoordinates = mutableMapOf<String, LayoutCoordinates>()
    fun visualBounds() = bounds() + itemCoordinates.filterValues { it.isAttached }.mapValues { (_, coordinates) ->
        viewportCoordinates?.takeIf { it.isAttached }?.localBoundingBoxOf(coordinates, clipBounds = false)
            ?: run {
                val at = coordinates.positionInRoot() - viewportOrigin
                Rect(at, androidx.compose.ui.geometry.Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
            }
    }
    var drag by mutableStateOf<KeyedDrag<T, P>?>(null)
        private set
    var optimistic by mutableStateOf<List<T>?>(null)
        private set
    private var optimisticBase: List<String>? = null
    var viewportOrigin by mutableStateOf(Offset.Zero)
    var viewportHeight by mutableIntStateOf(0)
    var viewportWidth by mutableIntStateOf(0)
    var obscuredBottom by mutableFloatStateOf(0f)
    var cancelRadius = 0f
    var cancelBottom = 0f
    var onCancelHover: (Boolean) -> Unit = {}
    val cancelCenter get() = Offset(viewportWidth / 2f, viewportHeight - cancelBottom - cancelRadius)
    fun rendered(fallback: List<T>): List<T?> = drag?.let { d ->
        d.dropItems ?: (d.remaining.take(d.insertion) + listOf(null) + d.remaining.drop(d.insertion))
    } ?: optimistic ?: fallback
    fun reconcile(current: List<T>) {
        items = current
        if (optimistic != null && (current.map { keyOf(it) } != optimisticBase ||
                optimistic?.map { keyOf(it) } == current.map { keyOf(it) })) optimistic = null
    }
    fun bounds() = grid.layoutInfo.visibleItemsInfo.associate { item -> item.key.toString() to
        Rect(item.offset.x.toFloat(), item.offset.y.toFloat(), (item.offset.x + item.size.width).toFloat(), (item.offset.y + item.size.height).toFloat()) }
    fun hit(point: Offset) = grid.layoutInfo.visibleItemsInfo.firstOrNull {
        point.x >= it.offset.x && point.x < it.offset.x + it.size.width && point.y >= it.offset.y && point.y < it.offset.y + it.size.height
    }?.key?.toString()?.let { key -> items.firstOrNull { keyOf(it) == key } }
    fun begin(item: T, selected: Set<String>, point: Offset): Boolean {
        val rects = visualBounds()
        val held = rects[keyOf(item)] ?: return false
        val moving = items.filter { keyOf(it) in selected }
        if (moving.isEmpty()) return false
        drag = KeyedDrag(items, moving, keyOf, rects.filterKeys { it in selected }, point - held.topLeft, point, held.size, capturePresentation())
        return true
    }
    fun move(point: Offset) { drag?.takeUnless { it.dropping }?.let { it.pointer = point
        val hovering = (point - cancelCenter).getDistance() <= cancelRadius
        if (hovering != it.overCancel) { it.overCancel = hovering; onCancelHover(hovering) }
        if (!hovering) updateInsertion(it) } }
    private fun updateInsertion(d: KeyedDrag<T, P>) {
        val visible = grid.layoutInfo.visibleItemsInfo
        val gap = visible.firstOrNull { it.key == DropGapKey }
        if (gap != null && d.pointer.y in gap.offset.y.toFloat()..(gap.offset.y + gap.size.height).toFloat() &&
            d.pointer.x in gap.offset.x.toFloat()..(gap.offset.x + gap.size.width).toFloat()) return
        val candidates = visible.filter { v -> v.key in d.remainingPositions }.sortedBy { it.index }
        if (candidates.isEmpty()) return
        val columns = (visible.maxOfOrNull { it.column } ?: 0) + 1
        val next = candidates.firstOrNull {
            if (columns == 1) d.pointer.y < it.offset.y + it.size.height / 2f
            else d.pointer.y < it.offset.y || (d.pointer.y < it.offset.y + it.size.height &&
                d.pointer.x < it.offset.x + it.size.width / 2f)
        }
        val anchor = next ?: candidates.last()
        val insertion = (d.remainingPositions.getValue(anchor.key.toString()) + if (next == null) 1 else 0).coerceIn(0, d.remaining.size)
        if (insertion != d.insertion) {
            // Reordering changes the slot content, not the viewport. LazyGrid otherwise follows
            // its first item's key (sometimes the moving gap), jumping a row during upward drags.
            // Request the same index/offset for this remeasure; placement animations stay intact.
            grid.requestScrollToItem(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset)
            d.insertion = insertion
        }
    }
    suspend fun autoScroll(edge: Float) {
        fun effectiveEdge() = minOf(edge, (viewportHeight - obscuredBottom).coerceAtLeast(0f) / 2f)
        fun speed(d: KeyedDrag<T, P>): Float {
            val zone = effectiveEdge()
            return when {
                d.overCancel || zone <= 0f -> 0f
                d.pointer.y < zone -> -((zone - d.pointer.y) / zone).coerceIn(0f, 1f)
                d.pointer.y > viewportHeight - obscuredBottom - zone -> ((d.pointer.y - viewportHeight + obscuredBottom + zone) / zone).coerceIn(0f, 1f)
                else -> 0f
            }
        }
        var previousFrame = withFrameNanos { it }
        while (currentCoroutineContext().isActive) {
            val d = drag?.takeUnless { it.dropping } ?: return
            if (speed(d) == 0f) {
                snapshotFlow { d.dropping || speed(d) != 0f }.first { it }
                previousFrame = withFrameNanos { it }
            }
            val frame = withFrameNanos { it }
            val seconds = ((frame - previousFrame) / 1_000_000_000f).coerceIn(0f, 0.032f)
            previousFrame = frame
            if (d.dropping || drag !== d) return
            val velocity = speed(d)
            if (velocity != 0f) {
                val consumed = grid.scrollBy(velocity * effectiveEdge() * 12f * seconds)
                updateInsertion(d)
                if (consumed == 0f) {
                    val resting = d.pointer
                    snapshotFlow { d.dropping || d.pointer != resting }.first { it }
                    previousFrame = withFrameNanos { it }
                }
            }
        }
    }
    fun finish(cancel: Boolean, reducedMotion: Boolean, onMoved: () -> Unit = {}, commit: (Set<String>, String?, (Boolean) -> Unit) -> Unit) {
        val d = drag?.takeUnless { it.dropping } ?: return
        d.dropping = true
        val expected = if (cancel) items else d.ordered()
        grid.requestScrollToItem(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset)
        d.dropItems = expected
        scope.launch {
            val result = CompletableDeferred<Boolean>()
            if (cancel) result.complete(false)
            else commit(d.keys, d.remaining.getOrNull(d.insertion)?.let(keyOf)) { result.complete(it) }
            withFrameNanos { }; withFrameNanos { }
            d.destinations = visualBounds()
            d.landing.animateTo(1f, tween(if (reducedMotion) 0 else 350))
            val saved = if (cancel) false else withTimeoutOrNull(5000) { result.await() } == true
            if (!saved && !cancel) {
                d.returnFrom = d.destinations
                d.landing.snapTo(0f)
                d.dropItems = items
                withFrameNanos { }; withFrameNanos { }
                d.destinations = visualBounds()
                d.landing.animateTo(1f, tween(if (reducedMotion) 0 else 350))
            }
            if (saved && !optimisticCommit) {
                // Publish-before-acknowledgement controllers are authoritative. Never hide new
                // identities (or resurrect removed ones) behind the pointer's original snapshot.
                withFrameNanos { }; withFrameNanos { }
                if (items.map { keyOf(it) } != expected.map { keyOf(it) }) {
                    d.returnFrom = d.destinations
                    d.landing.snapTo(0f)
                    d.dropItems = items
                    withFrameNanos { }; withFrameNanos { }
                    d.destinations = visualBounds()
                    d.landing.animateTo(1f, tween(if (reducedMotion) 0 else 350))
                } else d.dropItems = items
            }
            if (saved && optimisticCommit && items.map { keyOf(it) } != expected.map { keyOf(it) }) {
                optimisticBase = items.map { keyOf(it) }
                optimistic = expected
            }
            if (saved) onMoved()
            d.handoff.animateTo(1f, tween(if (reducedMotion) 0 else 220))
            if (drag === d) drag = null
        }
    }
}

