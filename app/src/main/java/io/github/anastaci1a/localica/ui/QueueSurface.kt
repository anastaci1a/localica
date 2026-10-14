package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

internal val CollapsedQueueHeight = 64.dp

@Composable
internal fun BoxWithConstraintsScope.QueueSurface(progress: Animatable<Float, *>, settings: InterfaceSettings,
    onSettled: (Boolean) -> Unit, playerProgress: Float = 1f, content: @Composable ColumnScope.() -> Unit) {
    val scope = rememberCoroutineScope()
    val inset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val top = maxHeight - inset - CollapsedQueueHeight
    val density = LocalDensity.current
    val distance = with(density) { (top - status).toPx().coerceAtLeast(1f) }
    var start by remember { mutableFloatStateOf(1f) }
    var delta by remember { mutableFloatStateOf(0f) }
    fun settle(open: Boolean) {
        onSettled(open)
        scope.launch { progress.animateTo(if (open) 1f else 0f, tween(settings.motionDuration(400))) }
    }
    val currentSettle by rememberUpdatedState<(Boolean) -> Unit>(::settle)
    val listPull = remember(progress, distance, density.density) {
        object : NestedScrollConnection {
            var pulling = false
            var pulled = 0f
            fun move(dy: Float): Offset {
                val before = pulled
                pulled = (pulled + dy).coerceIn(0f, distance)
                val consumed = pulled - before
                if (pulled == 0f) pulling = false
                scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo(1f - pulled / distance) }
                return Offset(0f, consumed)
            }
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (pulling && source == NestedScrollSource.UserInput) move(available.y) else Offset.Zero

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // The list consumes its own scrolling first. Only a finger pulling beyond its
                // top starts dismissal; a fling reaching the top must never close the queue.
                if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                pulling = true
                return move(available.y)
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!pulling) return Velocity.Zero
                pulling = false
                currentSettle(settleOpen(-pulled / density.density, -available.y / density.density,
                    progress.value, opening = false))
                pulled = 0f
                return Velocity(0f, available.y)
            }
        }
    }
    val p = progress.value
    if (p > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = p * 0.2f }.clickable { settle(false) }) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.scrim) {}
    }
    FrostedSurface(Modifier.offset(y = top + (status - top) * p + 80.dp * (1f - playerProgress))
        .width(maxWidth).height(CollapsedQueueHeight + inset + (maxHeight - status - inset - CollapsedQueueHeight) * p)
        .graphicsLayer { alpha = ((playerProgress - .4f) / .6f).coerceIn(0f, 1f) }
        .then(if (playerProgress < .99f) Modifier.clearAndSetSemantics {} else Modifier)
        .draggable(rememberDraggableState { dy ->
            delta += dy
            scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo((start - delta / distance).coerceIn(0f, 1f)) }
        }, Orientation.Vertical, onDragStarted = { progress.stop(); start = progress.value; delta = 0f },
            onDragStopped = { velocity -> settle(settleOpen(-delta / density.density, -velocity / density.density, progress.value, start < .5f)) })
        .nestedScroll(listPull).testTag("queue-surface"), shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), darkening = .22f + .28f * p) {
        Column(Modifier.fillMaxSize().clipToBounds().padding(bottom = inset)) {
            Box(Modifier.fillMaxWidth().height(CollapsedQueueHeight).clickable(onClickLabel = if (p == 0f) "Open queue" else "Close queue") { settle(p < .5f) }
                .semantics { contentDescription = if (p == 0f) "Open queue" else "Close queue" }) {
                Box(Modifier.align(Alignment.TopCenter).padding(top = 8.dp).size(36.dp, 4.dp)
                    .background(Color.White.copy(alpha = .38f), RoundedCornerShape(2.dp)))
                Row(Modifier.fillMaxSize().padding(horizontal = 12.dp).padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(CompactControlSize))
                    Text("Queue", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(CompactControlSize))
                }
            }
            if (p > 0f) {
                Spacer(Modifier.height(8.dp))
                content()
            }
        }
    }
}
