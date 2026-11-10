package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.ui.layout.ScrollbarSlots
import io.github.anastaci1a.localica.ui.spec.ScrollbarSpec
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
 * Direct manipulation follows the finger; programmatic navigation uses ListMotion.
 * Position and size come from [ScrollbarModel]; the thumb is drawn at sub-pixel precision.
 */
@Composable
internal fun BrowseScrollbar(state: LazyGridState, items: List<BrowserItem>, sort: TrackSort, modifier: Modifier = Modifier, onUserScroll: () -> Unit = {}) {
    ListScrollbar(state, items, { state.canScrollForward || state.canScrollBackward }, { state.scrollbarFrame() },
        { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset },
        { index, offset -> state.scrollToItem(index, offset) }, modifier, onUserScroll) { target ->
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
        { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset },
        { index, offset -> state.scrollToItem(index, offset) }, modifier, onUserScroll) { null }
}

@Composable
private fun ListScrollbar(state: Any, contentKey: Any, scrollable: () -> Boolean, frame: () -> ScrollbarFrame,
    movement: () -> Pair<Int, Int>, scrollTo: suspend (Int, Int) -> Unit,
    modifier: Modifier, onUserScroll: () -> Unit, previewFor: (Int) -> String?) {
    if (!scrollable()) return
    val owner = LocalModalGestures.current
    val userScroll by rememberUpdatedState(onUserScroll)
    val model = remember(state, contentKey) { ScrollbarModel() }
    val latestScrollTo by rememberUpdatedState(scrollTo)
    val requests = remember { Channel<Triple<Int, Int, Long?>>(Channel.CONFLATED) }
    fun request(fraction: Float): Boolean {
        if (owner?.blocked == true) return false
        userScroll()
        val (index, offset) = model.target(fraction)
        return requests.trySend(Triple(index, offset, owner?.generation)).isSuccess
    }
    LaunchedEffect(state, owner) {
        for ((index, offset, generation) in requests) {
            // Cancelling one scroll mutation must not kill this request consumer.
            // Requests queued by an old contact cannot run after modal dismissal.
            launch {
                if (owner?.blocked != true && generation == owner?.generation) latestScrollTo(index, offset)
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
    var target by remember { mutableIntStateOf(0) }
    // Read in the layout, draw and semantics phases, so scrolling never recomposes the scrollbar.
    fun fraction() = model.observe(frame())
    ScrollbarSlots(state, model, movement, ::fraction,
        { track, minimum -> model.thumbSize(track, minimum, track * ScrollbarSpec.maximumTrackShare) },
        { dragging }, { dragging = it }, { value ->
            target = model.target(value).first
            request(value)
        }, ::request, { previewFor(target) }, LocalMotionSpeed.current, LocalReducedMotion.current,
        modifier)
}
