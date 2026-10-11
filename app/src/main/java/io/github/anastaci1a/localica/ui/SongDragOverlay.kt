package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.math.exp

@Composable
internal fun BoxScope.SongDragOverlay(state: SongDragState, reducedMotion: Boolean) {
    val drag = state.drag ?: return
    val density = LocalDensity.current
    val view = LocalView.current
    SideEffect {
        state.cancelRadius = with(density) { 30.dp.toPx() }
        state.cancelBottom = with(density) { 20.dp.toPx() } + state.obscuredBottom
        state.onCancelHover = { entering -> view.performHapticFeedback(if (entering)
            android.view.HapticFeedbackConstants.LONG_PRESS else android.view.HapticFeedbackConstants.CLOCK_TICK) }
    }
    val offset = with(density) { 7.dp.toPx() }
    val margin = with(density) { 8.dp.toPx() }
    val stackWidth = minOf(drag.cardSize.width, state.viewportWidth - margin * 6).coerceAtLeast(margin)
    val width = with(density) { stackWidth.toDp() }
    val height = with(density) { drag.cardSize.height.toDp() }
    var lifted by remember(drag) { mutableStateOf(false) }
    LaunchedEffect(drag) { delay(if (reducedMotion) 0 else 240); lifted = true }
    fun homePosition(): Offset {
        val raw = drag.pointer - drag.grab
        return raw.copy(x = raw.x.coerceIn(margin,
            (state.viewportWidth - stackWidth - offset * 2 - margin).coerceAtLeast(margin)))
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
    val shadowAlpha by animateFloatAsState(if (drag.dropping) 0f else 1f, tween(if (reducedMotion) 0 else 240), label = "Stack shadow")
    // One shared shadow reads as a single lifted bundle, even while its lower cards trail the pointer.
    Box(Modifier.zIndex(2f).graphicsLayer { translationX = homePosition().x; translationY = homePosition().y }
        .size(width + if (drag.moving.size > 1) 14.dp else 0.dp, height + if (drag.moving.size > 1) 14.dp else 0.dp)
        .graphicsLayer { alpha = shadowAlpha }.shadow(24.dp, Corners.card, clip = false))
    val cards = remember(drag, lifted, drag.destinations) { drag.moving.withIndex().filter { (index, item) -> index < 3 ||
        (!lifted && item.key in drag.initialBounds) || (drag.dropping && item.key in drag.destinations.orEmpty()) } }
    cards.reversed().forEach { (index, item) -> key(drag, item.key) {
        val destination = drag.destinations?.get(item.key)
        fun stackTarget() = homePosition() + Offset(offset * index.coerceAtMost(2), offset * index.coerceAtMost(2))
        val liftOffset = remember { Animatable((drag.initialBounds[item.key]?.topLeft ?: stackTarget()) - stackTarget(), Offset.VectorConverter) }
        LaunchedEffect(drag, reducedMotion) {
            if (reducedMotion) liftOffset.snapTo(Offset.Zero) else liftOffset.animateTo(Offset.Zero, tween(240))
        }
        // Read motion in placement, not composition: neither pointer movement nor trailing
        // frames should rebuild artwork/text or measure the card tree.
        fun heldPosition() = (if (index == 0 || reducedMotion) homePosition() else followers[(index - 1).coerceAtMost(1)]) +
            Offset(offset * index.coerceAtMost(2), offset * index.coerceAtMost(2)) + liftOffset.value
        val landingStart = remember(drag.dropping, destination, drag.returnFrom) {
            drag.returnFrom?.get(item.key)?.topLeft ?: heldPosition()
        }
        fun displayed() = if (!drag.dropping) heldPosition() else destination?.let {
            landingStart + (it.topLeft - landingStart) * drag.landing.value
        } ?: landingStart
        fun cardWidth() = stackWidth + ((destination?.width ?: stackWidth) - stackWidth) * drag.landing.value
        val alpha by animateFloatAsState(if (drag.destinations != null && destination == null) 0f else 1f,
            tween(if (reducedMotion) 0 else 300), label = "Song landing")
        Surface(Modifier.zIndex(3f + (drag.moving.size - index).toFloat() / drag.moving.size)
            .graphicsLayer { translationX = displayed().x; translationY = displayed().y }
            .width(with(density) { cardWidth().toDp() }).height(height).graphicsLayer { this.alpha = alpha * (1f - drag.handoff.value) }
            .testTag(if (index == 0) "drag-stack" else "drag-card-${item.key}"),
            shape = Corners.card, color = if (index == 0) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.secondaryContainer,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))) {
            if (index == 0 || !lifted || drag.dropping) Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                MediaArtwork(item.coverUris, Modifier.size(48.dp), shape = artworkShape(item.ref.kind))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    item.subtitle?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (index == 0 && drag.moving.size > 1 && !drag.dropping) Surface(
            Modifier.zIndex(5f).graphicsLayer {
                translationX = displayed().x + stackWidth - with(density) { 28.dp.toPx() }
                translationY = displayed().y - with(density) { 10.dp.toPx() }
            }
                .defaultMinSize(28.dp, 28.dp).semantics { contentDescription = "Dragging ${drag.moving.size} songs" },
            color = MaterialTheme.colorScheme.primary, shape = CircleShape) {
            Box(Modifier.padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                Text(drag.moving.size.toString(), style = MaterialTheme.typography.labelMedium)
            }
        }
    } }
    if (!drag.dropping) {
        val color by animateColorAsState(if (drag.overCancel) MaterialTheme.colorScheme.error.copy(alpha = .7f) else Color.Transparent,
            tween(if (reducedMotion) 0 else 160), label = "Cancel target")
        LiquidGlassSurface(Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp + with(density) { state.obscuredBottom.toDp() }).size(60.dp).zIndex(10f)
            .testTag("cancel-drag").semantics { contentDescription = "Release to cancel move"; stateDescription = if (drag.overCancel) "Cancel move" else "Drag here to cancel" },
            tint = color) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(androidx.compose.ui.res.painterResource(io.github.anastaci1a.localica.R.drawable.ic_close), null,
                    Modifier.size(26.dp), tint = if (drag.overCancel) MaterialTheme.colorScheme.onError else LocalContentColor.current)
            }
        }
    }
}
