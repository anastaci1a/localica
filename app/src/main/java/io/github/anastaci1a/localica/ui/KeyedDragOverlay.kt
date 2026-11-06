package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.constrainHeight
import kotlin.math.roundToInt
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.first
import kotlin.math.exp

@Composable
internal fun <T : Any, P> BoxScope.KeyedDragOverlay(state: KeyedDragState<T, P>, reducedMotion: Boolean,
    countDescription: (Int) -> String, cancelBottomMargin: Dp = 20.dp, content: @Composable BoxScope.(T, P) -> Unit) {
    val drag = state.drag ?: return
    val density = LocalDensity.current
    val haptics = rememberControlHaptics()
    val cancelMotion = key(drag) { rememberFloatingControlMotion(!drag.dropping, reducedMotion) }
    DisposableEffect(drag, cancelMotion) {
        drag.cancelMotion = cancelMotion
        onDispose { drag.cancelMotion = null }
    }
    // The target can move under a stationary finger as it enters or a panel yields.
    LaunchedEffect(drag) {
        snapshotFlow { Triple(state.cancelCenter, state.cancelRadius, state.cancelInteractive) }.collect { state.refreshCancelHover() }
    }
    val cancelSize = minOf(with(density) { CompactControlSize.toPx() }, state.viewportHeight.toFloat()).coerceAtLeast(1f)
    SideEffect {
        state.cancelRadius = cancelSize / 2f
        state.cancelMargin = with(density) { cancelBottomMargin.toPx() }
        state.cancelRise = with(density) { FloatingControlRise.toPx() }
        state.onCancelHover = { entering -> haptics.emit(if (entering)
            ControlHaptic.Strong else ControlHaptic.Weak) }
    }
    val offset = with(density) { 7.dp.toPx() }
    val margin = with(density) { 8.dp.toPx() }
    val trailingWidth = if (drag.moving.size > 1) offset * 2 else 0f
    val stackWidth = minOf(drag.cardSize.width, state.viewportWidth - margin * (if (drag.moving.size > 1) 6 else 2)).coerceAtLeast(margin)
    val flight = remember(drag) { Animatable(0f) }
    var lifted by remember(drag) { mutableStateOf(reducedMotion) }
    fun flightProgress() = if (reducedMotion) 1f else flight.value.coerceIn(0f, 1f)
    LaunchedEffect(drag, reducedMotion) {
        if (reducedMotion) flight.snapTo(1f) else flight.animateTo(1f, tween(240))
        lifted = true
    }
    fun homePosition(): Offset {
        val raw = drag.pointer - drag.grab
        return raw.copy(x = raw.x.coerceIn(margin,
            (state.viewportWidth - stackWidth - trailingWidth - margin).coerceAtLeast(margin)))
    }
    // Sample all trailing cards together. A stale trailing *delta* added to the latest
    // pointer used to jump forward on input, then backward on the next animation frame.
    // Absolute frame positions avoid that feedback, and layer translation skips layout.
    var followers by remember(drag) { mutableStateOf(List(2) { homePosition() }) }
    LaunchedEffect(drag, reducedMotion) {
        if (reducedMotion || drag.moving.size < 2) return@LaunchedEffect
        var previousFrame = withFrameNanos { it }
        while (!drag.dropping) {
            if (followers.all { (homePosition() - it).getDistance() < 0.1f }) {
                val resting = homePosition()
                followers = List(2) { resting }
                snapshotFlow { homePosition() to drag.dropping }.first { it.second || (it.first - resting).getDistance() >= 0.1f }
                if (drag.dropping) break
            }
            val frame = withFrameNanos { it }
            val seconds = ((frame - previousFrame) / 1_000_000_000f).coerceIn(0f, 0.032f)
            previousFrame = frame
            val target = homePosition()
            followers = followers.mapIndexed { index, previous ->
                val next = previous + (target - previous) * (1f - exp(-seconds / (0.012f + index * 0.006f)))
                val difference = next - target
                val distance = difference.getDistance()
                val maximum = offset * (index + 1)
                if (distance > maximum) target + difference * (maximum / distance) else next
            }
        }
    }
    // Every card shares its item's outline (#532); trailing cards keep their last measurement.
    val outlines = remember(drag) { mutableMapOf<String, MediaItemOutlineHolder>() }
    fun outlineHolder(item: T) = outlines.getOrPut(state.keyOf(item)) { MediaItemOutlineHolder() }
    val artless = with(density) { ArtlessOutlineCorner.toPx() }
    fun cardOutline(holder: MediaItemOutlineHolder, width: Float, height: Float) = holder.value
        ?: MediaItemOutline(androidx.compose.ui.geometry.Rect(0f, 0f, width, height), minOf(artless, width / 2f, height / 2f))
    // Continue each item's shared hover outline while sampling the live scene under the stack.
    val backing = LocalFrostedBackdrop.current ?: state.hoverBacking
    BackdropLease(backing)
    val defaultRoom = with(density) { (HoverLift.MaxLift / 2).toPx() }.let { androidx.compose.ui.geometry.Rect(it, it, it, it) }
    // Plain map: the underlay reads it while drawing; every frame lambda reads live motion state.
    val frames = remember(drag) { mutableMapOf<String, HoverCardFrame>() }
    fun liftProgress(key: String): Float {
        val initial = drag.lifts[key]?.progress ?: 1f
        // A drag can acquire a selection that is still rising; continue from that contour.
        return (initial + (1f - initial) * flightProgress()) *
            (if (drag.committed) 1f - drag.handoff.value.coerceIn(0f, 1f) else 1f)
    }
    HoverDragUnderlay({ drag.moving.mapNotNull { frames[state.keyOf(it)] } }, backing, Modifier.matchParentSize().zIndex(2f))
    val cards = remember(drag, lifted, drag.destinations) { drag.moving.withIndex().filter { (index, item) -> index < 3 ||
        (!lifted && state.keyOf(item) in drag.initialBounds) || (drag.dropping && state.keyOf(item) in drag.destinations.orEmpty()) } }
    var previousPane = backing
    cards.reversed().forEach { (index, item) -> key(drag, state.keyOf(item)) {
        val paneBacking = previousPane
        // The top pane still samples its backing even though no later pane needs its recording.
        if (index == 0) BackdropLease(paneBacking)
        // Capture after the pane records its changed content/backing, not when composition
        // first sees the new backing (which can precede that draw).
        val paneScene = if (index > 0) rememberFrostedBackdrop(drag,
            // Consumers already clip to their pane. Avoid a full-viewport offscreen raster
            // that redraws everything outside those clips whenever a card moves.
            dark = backing?.dark == true, retainRaster = false) else null
        previousPane = paneScene
        val itemKey = state.keyOf(item)
        val destination = drag.destinations?.get(itemKey)
        val holder = outlineHolder(item)
        fun stackTarget() = homePosition() + Offset(offset * index.coerceAtMost(2), offset * index.coerceAtMost(2))
        val start = remember { (drag.initialBounds[itemKey]?.topLeft ?: stackTarget()) - stackTarget() }
        val liftOffset = remember { Animatable(start, Offset.VectorConverter) }
        LaunchedEffect(drag, reducedMotion) {
            if (reducedMotion) liftOffset.snapTo(Offset.Zero) else liftOffset.animateTo(Offset.Zero, tween(240))
        }
        // Read motion in placement, not composition: neither pointer movement nor trailing
        // frames should rebuild artwork/text or measure the card tree.
        fun heldPosition() = (if (index == 0 || reducedMotion) homePosition() else followers[(index - 1).coerceAtMost(1)]) +
            Offset(offset * index.coerceAtMost(2), offset * index.coerceAtMost(2)) + liftOffset.value
        val landingStart = remember(drag.dropping, drag.returnFrom) {
            drag.returnFrom?.get(itemKey)?.topLeft ?: heldPosition()
        }
        fun displayed() = if (!drag.dropping) heldPosition() else destination?.let {
            landingStart + (it.topLeft - landingStart) * drag.landing.value
        } ?: landingStart
        val originalSize = drag.initialBounds[itemKey]?.size ?: drag.cardSize
        fun heldWidth() = originalSize.width + (stackWidth - originalSize.width) * flightProgress()
        fun heldHeight() = originalSize.height + (drag.cardSize.height - originalSize.height) * flightProgress()
        val landingWidth = remember(drag.dropping, drag.returnFrom) {
            androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { drag.returnFrom?.get(itemKey)?.width ?: heldWidth() }
        }
        val landingHeight = remember(drag.dropping, drag.returnFrom) {
            androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { drag.returnFrom?.get(itemKey)?.height ?: heldHeight() }
        }
        fun cardWidth() = if (!drag.dropping) heldWidth() else
            landingWidth + ((destination?.width ?: landingWidth) - landingWidth) * drag.landing.value
        fun cardHeight() = if (!drag.dropping) heldHeight() else
            landingHeight + ((destination?.height ?: landingHeight) - landingHeight) * drag.landing.value
        val alpha by animateFloatAsState(if (drag.destinations != null && destination == null) 0f else 1f,
            tween(if (reducedMotion) 0 else 300), label = "Song landing")
        val rest = drag.initialBounds[itemKey]?.topLeft
        // Sample the moving viewport, with the greatest displacement on the top pane.
        fun sampleShift() = Offset(offset, offset) *
            ((3 - index.coerceAtMost(2)) * flightProgress() *
                (if (drag.dropping) 1f - drag.landing.value else 1f))
        val frame = run {
            HoverCardFrame(
                position = { displayed() },
                geometry = { hoverGeometry(cardOutline(holder, cardWidth(), cardHeight()), drag.lifts[itemKey]?.room ?: defaultRoom, liftProgress(itemKey)) },
                sampleShift = { sampleShift() },
                restOutline = { rest?.let { at -> (drag.lifts[itemKey]?.rest?.bounds ?: cardOutline(holder, originalSize.width, originalSize.height).bounds).translate(at) } },
                destinationOutline = { destination?.let { at -> cardOutline(holder, at.width, at.height).bounds.translate(at.topLeft) } },
                flight = { flightProgress() },
                landing = { if (drag.dropping) drag.landing.value else 0f },
                alpha = { alpha * (1f - drag.handoff.value.coerceIn(0f, 1f)) })
        }
        SideEffect { frames[itemKey] = frame }
        DisposableEffect(itemKey) { onDispose { frames.remove(itemKey) } }
        Box(Modifier.matchParentSize()
            .zIndex(3f + (drag.moving.size - index).toFloat() / drag.moving.size)
            .then(if (paneScene != null) Modifier.recordFrostedBackdrop(paneScene, paneBacking,
                displayBacking = false) else Modifier)) {
            HoverDragCard(Modifier
                .graphicsLayer { translationX = displayed().x; translationY = displayed().y }
                .layout { measurable, constraints ->
                    // Fitting/landing sizes remeasure; pointer and followers stay draw-only.
                    val width = constraints.constrainWidth(cardWidth().roundToInt().coerceAtLeast(0))
                    val height = constraints.constrainHeight(cardHeight().roundToInt().coerceAtLeast(0))
                    val child = measurable.measure(Constraints.fixed(width, height))
                    layout(width, height) { child.place(0, 0) }
                }
                .testTag(if (index == 0) "drag-stack" else "drag-card-$itemKey"),
                frame, paneBacking, stackShadow = { flightProgress() * (1f - if (drag.dropping) drag.landing.value else 0f) },
                // Complementary source-over fades expose the backdrop at mid-handoff.
                // Keep the outgoing foreground opaque above the warming destination row.
                contentAlpha = { if (drag.dropping && destination != null) alpha else frame.alpha() }) {
                if (index == 0 || !lifted || drag.dropping) CompositionLocalProvider(LocalHeldItemOutline provides holder) { content(item, drag.presentation) }
            }
        }
        if (index == 0 && drag.moving.size > 1 && !drag.dropping) Surface(
            Modifier.zIndex(5f).graphicsLayer {
                translationX = displayed().x + cardWidth() - with(density) { 28.dp.toPx() }
                translationY = displayed().y - with(density) { 10.dp.toPx() }
            }
                .defaultMinSize(28.dp, 28.dp).semantics { contentDescription = countDescription(drag.moving.size) },
            color = MaterialTheme.colorScheme.primary, shape = CircleShape) {
            Box(Modifier.padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                Text(drag.moving.size.toString(), style = MaterialTheme.typography.labelMedium)
            }
        }
    } }
    if (!drag.dropping) {
        DragCancelTarget(drag.overCancel, "move",
            Modifier.align(Alignment.BottomCenter)
                // Read late-published panel occupancy during placement, as hit testing does.
                .glassShadowOffset { IntOffset(0, state.cancelOffsetY) }
                .glassShadowOpacity { cancelMotion.alpha }
                .size(with(density) { cancelSize.toDp() })
                .zIndex(10f).testTag("cancel-drag"))
    }
}
