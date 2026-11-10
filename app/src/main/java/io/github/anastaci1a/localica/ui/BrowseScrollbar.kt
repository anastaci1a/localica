package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.unit.IntSize
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
import kotlinx.coroutines.flow.collectLatest

/**
 * Direct manipulation follows the finger; programmatic navigation uses ListMotion.
 * Position and size come from [ScrollbarModel]; the thumb is drawn at sub-pixel precision.
 */
@Composable
internal fun BrowseScrollbar(state: LazyGridState, items: List<BrowserItem>, sort: TrackSort, modifier: Modifier = Modifier, onUserScroll: () -> Unit = {}) {
    ListScrollbar(state, items, { state.canScrollForward || state.canScrollBackward }, { state.scrollbarFrame() },
        { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset },
        { index, offset -> state.jumpToItem(index, offset) }, modifier, onUserScroll) { target ->
        // Grid item 0 is the header; browser items start at grid index 1.
        items.getOrNull(target - 1)?.let { item -> when (sort) {
            TrackSort.Title -> item.title.firstOrNull()?.uppercase() ?: "#"
            TrackSort.Year -> item.songs.mapNotNull { it.track.year }.minOrNull()?.toString() ?: "—"
            else -> null
        } }
    }
}

/**
 * A cold jump that never makes the grid scroll back (#635).
 *
 * Inside a LookaheadScope, a lookahead pass that cannot fill the viewport from the requested item
 * scrolls back, and the grid then eases its approach pass across that distance with a spring:
 * every row is drawn away from its slot and drifts back. Targets near the end therefore land on an
 * item that still fills the viewport, and the rest is covered by a scroll delta, which stops at the
 * end without a scroll-back. Both steps measure synchronously, so only the final frame is drawn.
 */
internal suspend fun LazyGridState.jumpToItem(index: Int, scrollOffset: Int = 0) {
    val total = layoutInfo.totalItemsCount
    // Twice the visible items spans at least a viewport of similar rows before the end.
    val reach = (layoutInfo.visibleItemsInfo.size * 2).coerceAtLeast(1)
    if (index + reach < total) return scrollToItem(index, scrollOffset)
    scrollToItem((total - reach).coerceAtLeast(0).coerceAtMost(index))
    repeat(8) {
        val target = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        if (target != null) {
            val distance = (if (layoutInfo.orientation == Orientation.Vertical)
                target.offset.y else target.offset.x) + scrollOffset
            if (distance != 0) scrollBy(distance.toFloat())
            return
        }
        if (!canScrollForward) return
        scrollBy(layoutInfo.viewportSize.height.toFloat())
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
    val scope = rememberCoroutineScope()
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
    val density = LocalDensity.current
    // After a drag the list settles at an item or step target; the thumb eases from the finger to the true position.
    val release = remember { Animatable(0f) }
    var pressed by remember { mutableStateOf(false) }
    var activity by remember { mutableIntStateOf(0) }
    val opacity = remember { Animatable(1f) }
    val latestMovement by rememberUpdatedState(movement)
    LaunchedEffect(state) {
        snapshotFlow { latestMovement() to activity }.collectLatest {
            opacity.snapTo(1f)
            // Delayed animation uses the frame clock. A held, motionless contact also becomes idle.
            opacity.animateTo(0f, tween(durationMillis = 220, delayMillis = 1200))
        }
    }
    val reduced = LocalReducedMotion.current
    val speed = LocalMotionSpeed.current
    val grip by animateFloatAsState(if (dragging || pressed) ScrollbarGrip.HeldScale else 1f,
        tween(ScrollbarGrip.durationMillis(speed, reduced), easing = CubicBezierEasing(0f, 0f, .2f, 1f)), label = "Scrollbar grip")
    BoxWithConstraints(modifier.fillMaxHeight().width(76.dp).then(ScrollbarTapSharing).padding(top = 8.dp, bottom = 80.dp)) {
        val track = with(density) { maxHeight.toPx().coerceAtLeast(1f) }
        val minThumb = with(density) { ScrollbarGrip.MinHeightDp.dp.toPx() }
        var dragFraction by remember { mutableFloatStateOf(0f) }
        var dragY by remember { mutableFloatStateOf(0f) }
        var grab by remember { mutableFloatStateOf(0f) }
        fun thumb(): Float { fraction(); return model.thumbSize(track, minThumb, track * ScrollbarGrip.MaxTrackShare) * grip }
        fun displayed() = if (dragging) dragFraction else (fraction() + release.value).coerceIn(0f, 1f)
        fun position() = ScrollbarGrip.top(displayed(), track, thumb())
        fun seek(y: Float) {
            activity++
            dragY = y
            dragFraction = ((y - grab - thumb() / 2) / (track - thumb()).coerceAtLeast(1f)).coerceIn(0f, 1f)
            target = model.target(dragFraction).first
            request(dragFraction)
        }
        fun endDrag() {
            if (!dragging) return
            dragging = false
            val gap = dragFraction - fraction()
            scope.launch { release.snapTo(gap); release.animateTo(0f, tween(180)) }
        }
        Box(Modifier.align(Alignment.CenterEnd).width(ScrollbarGrip.HitWidthDp.dp).fillMaxHeight().then(ScrollbarTapSharing).testTag("browse-scrollbar")
            .semantics {
                contentDescription = "Scroll list"
                progressBarRangeInfo = ProgressBarRangeInfo(fraction(), 0f..1f)
                setProgress { request(it.coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    activity++
                    pressed = true
                    try {
                        do { val event = awaitPointerEvent(PointerEventPass.Initial) } while (event.changes.any { it.pressed })
                    } finally { pressed = false }
                }
            }
            .pointerInput(model) {
                // The strip shares input with the list beneath, so the drag is claimed on the Initial pass:
                // the list's own Main-pass scroll then sees a consumed stream, while taps stay unconsumed for row actions.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var travel = 0f
                    try {
                        while (true) {
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val dy = change.position.y - change.previousPosition.y
                            if (!dragging) {
                                if (change.isConsumed) break
                                travel += dy
                                if (kotlin.math.abs(travel) < viewConfiguration.touchSlop) continue
                                // Grabbing the thumb keeps its offset under the finger; elsewhere the thumb comes to the finger.
                                val top = position()
                                grab = if (down.position.y in top..(top + thumb())) down.position.y - (top + thumb() / 2) else 0f
                                dragging = true; seek(down.position.y + travel)
                            } else seek(dragY + dy)
                            change.consume()
                        }
                    } finally {
                        endDrag()
                    }
                }
            }) {
            Box(Modifier.align(Alignment.TopEnd).offset(x = (-4).dp)
                .graphicsLayer { alpha = opacity.value; translationY = position(); scaleX = grip; scaleY = grip; transformOrigin = TransformOrigin(1f, 0f) }
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
        val preview = previewFor(target)
        if (dragging && preview != null) Surface(Modifier.align(Alignment.TopStart)
            .graphicsLayer { alpha = opacity.value; translationY = position() }.testTag("scroll-preview"),
            shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Box(Modifier.defaultMinSize(48.dp, 48.dp).padding(8.dp), contentAlignment = Alignment.Center) {
                Text(preview, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** The edge strip takes drags, while unconsumed taps still reach a row's trailing action. */
private object ScrollbarTapSharing : ModifierNodeElement<ScrollbarTapSharingNode>() {
    override fun create() = ScrollbarTapSharingNode()
    override fun update(node: ScrollbarTapSharingNode) = Unit
    override fun equals(other: Any?) = this === other
    override fun hashCode() = javaClass.hashCode()
    override fun InspectorInfo.inspectableProperties() { name = "scrollbarTapSharing" }
}

private class ScrollbarTapSharingNode : Modifier.Node(), PointerInputModifierNode {
    override fun sharePointerInputWithSiblings() = true
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) = Unit
    override fun onCancelPointerInput() = Unit
}
