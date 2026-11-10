package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.ui.layout.ScrollbarSlots
import io.github.anastaci1a.localica.ui.spec.ScrollbarSpec
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.github.anastaci1a.localica.browse.BrowserItem
import io.github.anastaci1a.localica.library.TrackSort
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Direct manipulation moves the list by the finger's delta (#704); programmatic navigation uses ListMotion.
 * Position and size come from [ScrollbarModel]; the thumb is drawn at sub-pixel precision.
 */
@Composable
internal fun BrowseScrollbar(state: LazyGridState, items: List<BrowserItem>, sort: TrackSort, modifier: Modifier = Modifier, onUserScroll: () -> Unit = {}) {
    ListScrollbar(state, items, { state.canScrollForward || state.canScrollBackward }, { state.scrollbarFrame() },
        { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }, { state.isScrollInProgress },
        { index, offset -> state.scrollToItem(index, offset) }, { state.scrollBy(it) }, modifier, onUserScroll) { target ->
        // Grid item 0 is the header; browser items start at grid index 1.
        items.getOrNull(target - 1)?.let { item -> when (sort) {
            TrackSort.Title -> item.title.firstOrNull()?.uppercase() ?: "#"
            TrackSort.Year -> item.songs.mapNotNull { it.track.year }.minOrNull()?.toString() ?: "—"
            else -> null
        } }
    }
}

@Composable
internal fun BrowseScrollbar(state: LazyListState, modifier: Modifier = Modifier, onUserScroll: () -> Unit = {}) {
    ListScrollbar(state, state, { state.canScrollForward || state.canScrollBackward }, { state.scrollbarFrame() },
        { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }, { state.isScrollInProgress },
        { index, offset -> state.scrollToItem(index, offset) }, { state.scrollBy(it) }, modifier, onUserScroll) { null }
}

@Composable
private fun ListScrollbar(state: Any, contentKey: Any, scrollable: () -> Boolean, frame: () -> ScrollbarFrame,
    movement: () -> Pair<Int, Int>, scrolling: () -> Boolean, scrollTo: suspend (Int, Int) -> Unit,
    scrollBy: suspend (Float) -> Float, modifier: Modifier, onUserScroll: () -> Unit, previewFor: (Int) -> String?) {
    if (!scrollable()) return
    val owner = LocalModalGestures.current
    val userScroll by rememberUpdatedState(onUserScroll)
    val model = remember(state, contentKey) { ScrollbarModel() }
    val drag = remember(state, contentKey) { ScrollbarDrag() }
    val latestScrollTo by rememberUpdatedState(scrollTo)
    val latestScrollBy by rememberUpdatedState(scrollBy)
    // An absolute (semantics) target, or NaN while the drag owes relative movement.
    val absolute = remember { FloatArray(1) { Float.NaN } }
    val requests = remember { Channel<Long?>(Channel.CONFLATED) }
    fun wake(): Boolean {
        if (owner?.blocked == true) return false
        userScroll()
        return requests.trySend(owner?.generation).isSuccess
    }
    fun request(fraction: Float): Boolean { absolute[0] = fraction; return wake() }
    LaunchedEffect(state, owner) {
        for (generation in requests) {
            val target = absolute[0]
            absolute[0] = Float.NaN
            // Owed drag travel is taken now, from the latest measured position, so conflated events lose none.
            val step = drag.take(model.position, model.remaining)
            // Cancelling one scroll mutation must not kill this request consumer.
            // Requests queued by an old contact cannot run after modal dismissal.
            launch {
                if (owner?.blocked == true || generation != owner?.generation) return@launch
                when {
                    !target.isNaN() -> model.target(target).let { (index, offset) -> latestScrollTo(index, offset) }
                    step == Float.POSITIVE_INFINITY -> model.target(1f).let { (index, offset) -> latestScrollTo(index, offset) }
                    step == Float.NEGATIVE_INFINITY -> latestScrollTo(0, 0)
                    step == 0f -> Unit
                    // Near steps scroll through their content by exact pixels; a far step jumps that far.
                    kotlin.math.abs(step) <= model.viewport -> latestScrollBy(step)
                    else -> model.targetBy(step).let { (index, offset) -> latestScrollTo(index, offset) }
                }
            }.join()
            // At most one cold jump per frame: events arriving meanwhile conflate to the latest.
            withFrameNanos { }
        }
    }
    var dragging by remember { mutableStateOf(false) }
    OnModalGestureCancellation { dragging = false }
    // A drag is a far journey: keep cached covers, defer uncached artwork I/O until release.
    val transit = LocalArtworkTransit.current
    LaunchedEffect(dragging, transit) {
        if (dragging && transit != null) transit.during(true) { awaitCancellation() }
    }
    // Read in the layout, draw and semantics phases, so scrolling never recomposes the scrollbar.
    fun fraction() = model.observe(frame())
    ScrollbarSlots(state, model, movement, ::fraction,
        { track, minimum -> model.thumbSize(track, minimum, track * ScrollbarSpec.maximumTrackShare) },
        scrolling, { dragging }, { dragging = it }, { drag.fraction }, drag::begin, { drag.move(it); wake() }, ::request,
        { previewFor(movement().first) }, LocalMotionSpeed.current, LocalReducedMotion.current,
        modifier)
}
