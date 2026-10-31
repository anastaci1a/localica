package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.browse.BrowserItem
import io.github.anastaci1a.localica.library.TrackSort
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Direct manipulation follows the finger; programmatic navigation uses ListMotion. */
@Composable
internal fun BrowseScrollbar(state: LazyGridState, items: List<BrowserItem>, sort: TrackSort, modifier: Modifier = Modifier, onUserScroll: () -> Unit = {}) {
    if (items.size < 40 || (!state.canScrollForward && !state.canScrollBackward)) return
    val owner = LocalModalGestures.current
    val userScroll by rememberUpdatedState(onUserScroll)
    val requests = remember { Channel<Pair<Int, Long?>>(Channel.CONFLATED) }
    fun request(index: Int): Boolean {
        if (owner?.blocked == true) return false
        userScroll()
        return requests.trySend(index to owner?.generation).isSuccess
    }
    LaunchedEffect(state, owner) {
        for ((index, generation) in requests) {
            // Cancelling one scroll mutation must not kill this request consumer.
            // Requests queued by an old contact cannot run after modal dismissal.
            launch {
                if (owner?.blocked != true && generation == owner?.generation) state.scrollToItem(index + 1)
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
    val lastStart = items.lastIndex
    // Read in the layout and semantics phases, so scrolling never recomposes the scrollbar.
    fun fraction() = if (!state.canScrollForward) 1f else ((state.firstVisibleItemIndex - 1).coerceAtLeast(0).toFloat() / lastStart).coerceIn(0f, 1f)
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxHeight().width(76.dp).padding(top = 8.dp, bottom = 80.dp)) {
        val thumbHeight = 48.dp
        val travel = with(density) { (maxHeight - thumbHeight).toPx().coerceAtLeast(1f) }
        val halfThumb = with(density) { thumbHeight.toPx() / 2 }
        var dragY by remember { mutableFloatStateOf(0f) }
        fun position() = if (dragging) (dragY - halfThumb).coerceIn(0f, travel) else travel * fraction()
        fun seek(y: Float) {
            dragY = y
            target = (((y - halfThumb) / travel).coerceIn(0f, 1f) * lastStart).roundToInt()
            request(target)
        }
        Box(Modifier.align(Alignment.CenterEnd).width(20.dp).fillMaxHeight().testTag("browse-scrollbar")
            .semantics {
                contentDescription = "Scroll list"
                progressBarRangeInfo = ProgressBarRangeInfo(fraction(), 0f..1f)
                setProgress { request((it.coerceIn(0f, 1f) * lastStart).roundToInt()) }
            }
            .pointerInput(lastStart, travel) {
                detectVerticalDragGestures(onDragStart = { dragging = true; seek(it.y) },
                    onDragEnd = { dragging = false }, onDragCancel = { dragging = false }) { change, delta ->
                    change.consume(); seek(dragY + delta)
                }
            }) {
            Box(Modifier.align(Alignment.TopEnd).testTag("browse-scrollbar-thumb").offset { IntOffset(-4.dp.roundToPx(), position().roundToInt()) }
                .width(if (dragging) 6.dp else 4.dp).height(thumbHeight)
                .background(if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)))
        }
        val preview = items.getOrNull(target)?.let { item -> when (sort) {
            TrackSort.Title -> item.title.firstOrNull()?.uppercase() ?: "#"
            TrackSort.Year -> item.songs.mapNotNull { it.track.year }.minOrNull()?.toString() ?: "—"
            else -> null
        } }
        if (dragging && preview != null) Surface(Modifier.align(Alignment.TopStart)
            .offset { IntOffset(0, position().roundToInt()) }.testTag("scroll-preview"),
            shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Box(Modifier.defaultMinSize(48.dp, 48.dp).padding(8.dp), contentAlignment = Alignment.Center) {
                Text(preview, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
