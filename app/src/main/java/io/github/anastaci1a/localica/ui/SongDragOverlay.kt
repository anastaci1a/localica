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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
internal fun BoxScope.SongDragOverlay(state: SongDragState, reducedMotion: Boolean) {
    val drag = state.drag ?: return
    val density = LocalDensity.current
    val view = LocalView.current
    SideEffect {
        state.cancelRadius = with(density) { 30.dp.toPx() }
        state.cancelBottom = with(density) { 20.dp.toPx() }
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
    val rawHome = drag.pointer - drag.grab
    val home = rawHome.copy(x = rawHome.x.coerceIn(margin,
        (state.viewportWidth - stackWidth - offset * 2 - margin).coerceAtLeast(margin)))
    val shadowAlpha by animateFloatAsState(if (drag.dropping) 0f else 1f, tween(if (reducedMotion) 0 else 240), label = "Stack shadow")
    // One shared shadow reads as a single lifted bundle, even while its lower cards trail the pointer.
    Box(Modifier.zIndex(2f).offset { IntOffset(home.x.roundToInt(), home.y.roundToInt()) }
        .size(width + if (drag.moving.size > 1) 14.dp else 0.dp, height + if (drag.moving.size > 1) 14.dp else 0.dp)
        .graphicsLayer { alpha = shadowAlpha }.shadow(24.dp, Corners.card, clip = false))
    val cards = remember(drag, lifted, drag.destinations) { drag.moving.withIndex().filter { (index, item) -> index < 3 ||
        (!lifted && item.key in drag.initialBounds) || (drag.dropping && item.key in drag.destinations.orEmpty()) } }
    cards.reversed().forEach { (index, item) -> key(drag, item.key) {
        val destination = drag.destinations?.get(item.key)
        val stackTarget = home + Offset(offset * index.coerceAtMost(2), offset * index.coerceAtMost(2))
        val target = destination?.topLeft ?: stackTarget
        val position = remember { Animatable(drag.initialBounds[item.key]?.topLeft ?: home, Offset.VectorConverter) }
        val cardWidth = remember { Animatable(drag.initialBounds[item.key]?.width ?: stackWidth) }
        LaunchedEffect(destination?.width, reducedMotion) {
            val targetWidth = destination?.width ?: stackWidth
            if (reducedMotion) cardWidth.snapTo(targetWidth) else cardWidth.animateTo(targetWidth, tween(350))
        }
        LaunchedEffect(target, drag.dropping, reducedMotion) {
            if (reducedMotion) position.snapTo(target)
            else position.animateTo(target, if (drag.dropping) tween(350) else spring(dampingRatio = 0.9f, stiffness = 950f / (index.coerceAtMost(2) + 1)))
        }
        val alpha by animateFloatAsState(if (drag.destinations != null && destination == null) 0f else 1f,
            tween(if (reducedMotion) 0 else 300), label = "Song landing")
        Surface(Modifier.zIndex(3f + (drag.moving.size - index).toFloat() / drag.moving.size)
            .offset { IntOffset(position.value.x.roundToInt(), position.value.y.roundToInt()) }
            .width(with(density) { cardWidth.value.toDp() }).height(height).graphicsLayer { this.alpha = alpha }
            .testTag(if (index == 0) "drag-stack" else "drag-card-${item.key}"),
            shape = Corners.card, color = if (index == 0) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.secondaryContainer,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))) {
            if (index == 0 || !lifted || drag.dropping) Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                MediaArtwork(item.coverUris, Modifier.size(48.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    item.subtitle?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (index == 0 && drag.moving.size > 1 && !drag.dropping) Surface(
            Modifier.zIndex(5f).offset { IntOffset((position.value.x + stackWidth - with(density) { 28.dp.toPx() }).roundToInt(),
                (position.value.y - with(density) { 10.dp.toPx() }).roundToInt()) }
                .defaultMinSize(28.dp, 28.dp).semantics { contentDescription = "Dragging ${drag.moving.size} songs" },
            color = MaterialTheme.colorScheme.primary, shape = CircleShape) {
            Box(Modifier.padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                Text(drag.moving.size.toString(), style = MaterialTheme.typography.labelMedium)
            }
        }
    } }
    if (!drag.dropping) {
        val color by animateColorAsState(if (drag.overCancel) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surfaceContainerHigh,
            tween(if (reducedMotion) 0 else 160), label = "Cancel target")
        Surface(Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp).size(60.dp).zIndex(10f)
            .testTag("cancel-drag").semantics { contentDescription = "Release to cancel move"; stateDescription = if (drag.overCancel) "Cancel move" else "Drag here to cancel" },
            shape = CircleShape, color = color, shadowElevation = 12.dp) {
            Box(contentAlignment = Alignment.Center) {
                Icon(androidx.compose.ui.res.painterResource(io.github.anastaci1a.localica.R.drawable.ic_close), null,
                    Modifier.size(26.dp), tint = if (drag.overCancel) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
