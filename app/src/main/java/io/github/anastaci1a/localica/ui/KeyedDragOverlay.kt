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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
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
    val width = with(density) { stackWidth.toDp() }
    val height = with(density) { drag.cardSize.height.toDp() }
    var lifted by remember(drag) { mutableStateOf(false) }
    LaunchedEffect(drag) { delay(if (reducedMotion) 0 else 240); lifted = true }
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
    // Cards continue each item's hover (#533): the same lift, shadow and carried backdrop cutout.
    val backing = state.hoverBacking ?: LocalFrostedBackdrop.current
    BackdropLease(backing)
    val defaultRoom = with(density) { (HoverLift.MaxLift / 2).toPx() }.let { androidx.compose.ui.geometry.Rect(it, it, it, it) }
    // Plain map: the underlay reads it while drawing; every frame lambda reads live motion state.
    val frames = remember(drag) { mutableMapOf<String, HoverCardFrame>() }
    fun liftProgress(key: String) = (drag.lifts[key]?.progress ?: 1f) *
        (if (drag.committed) 1f - drag.handoff.value.coerceIn(0f, 1f) else 1f)
    HoverDragUnderlay({ drag.moving.mapNotNull { frames[state.keyOf(it)] } }, backing, Modifier.matchParentSize().zIndex(2f))
    val cards = remember(drag, lifted, drag.destinations) { drag.moving.withIndex().filter { (index, item) -> index < 3 ||
        (!lifted && state.keyOf(item) in drag.initialBounds) || (drag.dropping && state.keyOf(item) in drag.destinations.orEmpty()) } }
    cards.reversed().forEach { (index, item) -> key(drag, state.keyOf(item)) {
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
        fun cardWidth() = stackWidth + ((destination?.width ?: stackWidth) - stackWidth) * drag.landing.value
        fun cardHeight() = drag.cardSize.height + ((destination?.height ?: drag.cardSize.height) - drag.cardSize.height) * drag.landing.value
        val alpha by animateFloatAsState(if (drag.destinations != null && destination == null) 0f else 1f,
            tween(if (reducedMotion) 0 else 300), label = "Song landing")
        // The cutout is carried from the rest position and lands at the destination's region.
        val rest = drag.initialBounds[itemKey]?.topLeft
        fun sample(): Offset {
            val from = rest ?: return displayed()
            val to = destination?.topLeft
            return if (drag.dropping && to != null) from + (to - from) * drag.landing.value else from
        }
        val frame = run {
            HoverCardFrame(
                position = { displayed() },
                geometry = { hoverGeometry(cardOutline(holder, cardWidth(), cardHeight()), drag.lifts[itemKey]?.room ?: defaultRoom, liftProgress(itemKey)) },
                sampleShift = { displayed() - sample() },
                restOutline = { rest?.let { at -> cardOutline(holder, drag.cardSize.width, drag.cardSize.height).bounds.translate(at) } },
                destinationOutline = { destination?.let { at -> cardOutline(holder, at.width, at.height).bounds.translate(at.topLeft) } },
                flight = { if (start.getDistance() < .5f) 1f else (1f - liftOffset.value.getDistance() / start.getDistance()).coerceIn(0f, 1f) },
                landing = { if (drag.dropping) drag.landing.value else 0f },
                alpha = { alpha * (1f - drag.handoff.value.coerceIn(0f, 1f)) })
        }
        SideEffect { frames[itemKey] = frame }
        DisposableEffect(itemKey) { onDispose { frames.remove(itemKey) } }
        HoverDragCard(Modifier.zIndex(3f + (drag.moving.size - index).toFloat() / drag.moving.size)
            .graphicsLayer { translationX = displayed().x; translationY = displayed().y }
            .width(with(density) { cardWidth().toDp() }).height(with(density) { cardHeight().toDp() })
            .testTag(if (index == 0) "drag-stack" else "drag-card-$itemKey"),
            frame, backing) {
            if (index == 0 || !lifted || drag.dropping) CompositionLocalProvider(LocalHeldItemOutline provides holder) { content(item, drag.presentation) }
        }
        if (index == 0 && drag.moving.size > 1 && !drag.dropping) Surface(
            Modifier.zIndex(5f).graphicsLayer {
                translationX = displayed().x + stackWidth - with(density) { 28.dp.toPx() }
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
                .offset { IntOffset(0, state.cancelOffsetY) }
                .graphicsLayer { alpha = cancelMotion.alpha }
                .size(with(density) { cancelSize.toDp() })
                .zIndex(10f).testTag("cancel-drag"))
    }
}
