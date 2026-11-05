package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
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
    if (items.size < 40 || (!state.canScrollForward && !state.canScrollBackward)) return
    val owner = LocalModalGestures.current
    val scope = rememberCoroutineScope()
    val userScroll by rememberUpdatedState(onUserScroll)
    val model = remember(state, items) { ScrollbarModel() }
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
                if (owner?.blocked != true && generation == owner?.generation) state.scrollToItem(index, offset)
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
    fun fraction() = model.observe(state.scrollbarFrame())
    val density = LocalDensity.current
    // After a drag the list settles at an item or step target; the thumb eases from the finger to the true position.
    val release = remember { Animatable(0f) }
    var pressed by remember { mutableStateOf(false) }
    val reduced = LocalReducedMotion.current
    val speed = LocalMotionSpeed.current
    val grip by animateFloatAsState(if (dragging || pressed) ScrollbarGrip.HeldScale else 1f,
        tween(ScrollbarGrip.durationMillis(speed, reduced), easing = CubicBezierEasing(0f, 0f, .2f, 1f)), label = "Scrollbar grip")
    BoxWithConstraints(modifier.fillMaxHeight().width(76.dp).padding(top = 8.dp, bottom = 80.dp)) {
        val track = with(density) { maxHeight.toPx().coerceAtLeast(1f) }
        val minThumb = with(density) { ScrollbarGrip.MinHeightDp.dp.toPx() }
        var dragFraction by remember { mutableFloatStateOf(0f) }
        var dragY by remember { mutableFloatStateOf(0f) }
        fun thumb(): Float { fraction(); return model.thumbSize(track, minThumb, track * ScrollbarGrip.MaxTrackShare) * grip }
                fun displayed() = if (dragging) dragFraction else (fraction() + release.value).coerceIn(0f, 1f)
        fun position() = ScrollbarGrip.top(displayed(), track, thumb())
        fun seek(y: Float) {
            dragY = y
            dragFraction = ((y - thumb() / 2) / (track - thumb()).coerceAtLeast(1f)).coerceIn(0f, 1f)
            target = model.target(dragFraction).first
            request(dragFraction)
        }
        fun endDrag() {
            if (!dragging) return
            dragging = false
            val gap = dragFraction - fraction()
            scope.launch { release.snapTo(gap); release.animateTo(0f, tween(180)) }
        }
        Box(Modifier.align(Alignment.CenterEnd).width(ScrollbarGrip.HitWidthDp.dp).fillMaxHeight().testTag("browse-scrollbar")
            .semantics {
                contentDescription = "Scroll list"
                progressBarRangeInfo = ProgressBarRangeInfo(fraction(), 0f..1f)
                setProgress { request(it.coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    pressed = true
                    try {
                        do { val event = awaitPointerEvent(PointerEventPass.Initial) } while (event.changes.any { it.pressed })
                    } finally { pressed = false }
                }
            }
            .pointerInput(model) {
                try {
                    detectVerticalDragGestures(onDragStart = { dragging = true; seek(it.y) },
                        onDragEnd = ::endDrag, onDragCancel = ::endDrag) { change, delta ->
                        change.consume(); seek(dragY + delta)
                    }
                } finally {
                    endDrag()
                }
            }) {
            Box(Modifier.align(Alignment.TopEnd).offset(x = (-4).dp)
                .graphicsLayer { translationY = position(); scaleX = grip; scaleY = grip; transformOrigin = TransformOrigin(1f, 0f) }
                .width(ScrollbarGrip.RestWidthDp.dp)
                // The resting length follows the model; it is read while measuring, so it never recomposes.
                .layout { measurable, constraints ->
                    fraction()
                    val h = model.thumbSize(track, minThumb, track * ScrollbarGrip.MaxTrackShare).roundToInt()
                    val p = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                    layout(p.width, p.height) { p.place(0, 0) }
                }.testTag("browse-scrollbar-thumb")
                .background(if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)))
        }
        // Grid item 0 is the list header; browser items start at grid index 1.
        val preview = items.getOrNull(target - 1)?.let { item -> when (sort) {
            TrackSort.Title -> item.title.firstOrNull()?.uppercase() ?: "#"
            TrackSort.Year -> item.songs.mapNotNull { it.track.year }.minOrNull()?.toString() ?: "—"
            else -> null
        } }
        if (dragging && preview != null) Surface(Modifier.align(Alignment.TopStart)
            .graphicsLayer { translationY = position() }.testTag("scroll-preview"),
            shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Box(Modifier.defaultMinSize(48.dp, 48.dp).padding(8.dp), contentAlignment = Alignment.Center) {
                Text(preview, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
