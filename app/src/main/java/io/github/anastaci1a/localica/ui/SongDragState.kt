package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.*
import io.github.anastaci1a.localica.browse.BrowserItem
import io.github.anastaci1a.localica.navigation.MediaRef
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

internal const val DropGapKey = "localica-drop-gap"

@Stable
internal class SongDrag(val original: List<BrowserItem>, val moving: List<BrowserItem>,
    val initialBounds: Map<String, Rect>, val grab: Offset, point: Offset, val cardSize: androidx.compose.ui.geometry.Size) {
    val keys = moving.map { it.key }.toSet()
    val remaining = original.filterNot { it.key in keys }
    val remainingPositions = remaining.withIndex().associate { it.value.key to it.index }
    var pointer by mutableStateOf(point)
    var insertion by mutableIntStateOf(original.indexOfFirst { it.key in keys }.coerceAtLeast(0))
    var overCancel by mutableStateOf(false)
    var dropping by mutableStateOf(false)
    var dropItems by mutableStateOf<List<BrowserItem>?>(null)
    var destinations by mutableStateOf<Map<String, Rect>?>(null)
    var returnFrom by mutableStateOf<Map<String, Rect>?>(null)
    val landing = Animatable(0f)
    val handoff = Animatable(0f)
    fun ordered() = remaining.take(insertion) + moving + remaining.drop(insertion)
}

@Stable
internal class SongDragState(val grid: LazyGridState, private val scope: CoroutineScope) {
    var items: List<BrowserItem> = emptyList()
    var drag by mutableStateOf<SongDrag?>(null)
        private set
    var optimistic by mutableStateOf<List<BrowserItem>?>(null)
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
    fun rendered(fallback: List<BrowserItem>): List<BrowserItem?> = drag?.let { d ->
        d.dropItems ?: (d.remaining.take(d.insertion) + listOf(null) + d.remaining.drop(d.insertion))
    } ?: optimistic ?: fallback
    fun reconcile(current: List<BrowserItem>) {
        items = current
        if (optimistic != null && (current.map { it.key } != optimisticBase ||
                optimistic?.map { it.key } == current.map { it.key })) optimistic = null
    }
    fun bounds() = grid.layoutInfo.visibleItemsInfo.associate { item -> item.key.toString() to
        Rect(item.offset.x.toFloat(), item.offset.y.toFloat(), (item.offset.x + item.size.width).toFloat(), (item.offset.y + item.size.height).toFloat()) }
    fun hit(point: Offset) = grid.layoutInfo.visibleItemsInfo.firstOrNull {
        point.x >= it.offset.x && point.x < it.offset.x + it.size.width && point.y >= it.offset.y && point.y < it.offset.y + it.size.height
    }?.key?.toString()?.let { key -> items.firstOrNull { it.key == key } }
    fun begin(item: BrowserItem, selected: Set<String>, point: Offset): Boolean {
        val rects = bounds()
        val held = rects[item.key] ?: return false
        val moving = items.filter { it.key in selected }
        if (moving.isEmpty()) return false
        drag = SongDrag(items, moving, rects.filterKeys { it in selected }, point - held.topLeft, point, held.size)
        return true
    }
    fun move(point: Offset) { drag?.takeUnless { it.dropping }?.let { it.pointer = point
        val hovering = (point - cancelCenter).getDistance() <= cancelRadius
        if (hovering != it.overCancel) { it.overCancel = hovering; onCancelHover(hovering) }
        if (!hovering) updateInsertion(it) } }
    private fun updateInsertion(d: SongDrag) {
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
        fun speed(d: SongDrag) = when {
            d.overCancel -> 0f
            d.pointer.y < edge -> -((edge - d.pointer.y) / edge).coerceIn(0f, 1f)
            d.pointer.y > viewportHeight - obscuredBottom - edge -> ((d.pointer.y - viewportHeight + obscuredBottom + edge) / edge).coerceIn(0f, 1f)
            else -> 0f
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
                val consumed = grid.scrollBy(velocity * edge * 12f * seconds)
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
        d.dropItems = expected
        scope.launch {
            val result = CompletableDeferred<Boolean>()
            if (cancel) result.complete(false)
            else commit(d.keys, d.remaining.getOrNull(d.insertion)?.key) { result.complete(it) }
            withFrameNanos { }; withFrameNanos { }
            d.destinations = bounds()
            d.landing.animateTo(1f, tween(if (reducedMotion) 0 else 350))
            val saved = if (cancel) false else withTimeoutOrNull(5000) { result.await() } == true
            if (!saved && !cancel) {
                d.returnFrom = d.destinations
                d.landing.snapTo(0f)
                d.dropItems = items
                withFrameNanos { }; withFrameNanos { }
                d.destinations = bounds()
                d.landing.animateTo(1f, tween(if (reducedMotion) 0 else 350))
            }
            if (saved && items.map { it.key } != expected.map { it.key }) {
                optimisticBase = items.map { it.key }
                optimistic = expected
            }
            if (saved) onMoved()
            d.handoff.animateTo(1f, tween(if (reducedMotion) 0 else 220))
            if (drag === d) drag = null
        }
    }
}

/** Own the held pointer at Initial pass so item clicks/scrolling cannot take over mid-drag. */
internal fun Modifier.songDrag(state: SongDragState, enabled: Boolean, source: MediaRef?, selection: SessionSelection,
    reducedMotion: Boolean, trailingActions: Boolean, commit: (Set<String>, String?, (Boolean) -> Unit) -> Unit): Modifier = if (!enabled) this else pointerInput(state, source, reducedMotion, trailingActions) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val item = state.hit(down.position) ?: return@awaitEachGesture
        val row = item.row ?: return@awaitEachGesture
        // The trailing action button keeps its own press/hold interaction.
        val bounds = state.bounds()[item.key] ?: return@awaitEachGesture
        if (trailingActions && down.position.x > bounds.right - 48 * density) return@awaitEachGesture
        val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                if (!change.pressed || change.isConsumed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull false
            }
            @Suppress("UNREACHABLE_CODE") false
        }
        if (held != null) return@awaitEachGesture
        selection.select(source, listOf(row))
        val selected = selection.entries.takeIf { entries -> entries.all { it.source == source && state.items.any { item -> item.key == it.row.id } } }
            ?.map { it.row.id }?.toSet()
        var moving = false
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (event.changes.count { it.pressed } > 1) break
                if (!change.pressed) {
                    // Compose delivers pointer cancellation as an already-consumed release.
                    val canceled = change.isConsumed
                    change.consume()
                    if (moving) {
                        if (!canceled) state.move(change.position)
                        state.finish(canceled || state.drag?.overCancel == true, reducedMotion, selection::clear, commit)
                    }
                    break
                }
                change.consume()
                if (!moving && selected != null && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                    moving = state.begin(item, selected, down.position)
                }
                if (moving) state.move(change.position)
            }
        } finally { if (moving && state.drag?.dropping == false) state.finish(true, reducedMotion, selection::clear, commit) }
    }
}
