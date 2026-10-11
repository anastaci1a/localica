package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

@Composable
internal fun BoxWithConstraintsScope.QueueSurface(progress: Animatable<Float, *>, settings: InterfaceSettings,
    onSettled: (Boolean) -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val scope = rememberCoroutineScope()
    val inset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val top = maxHeight - inset - 72.dp
    val density = LocalDensity.current
    val distance = with(density) { (top - status).toPx().coerceAtLeast(1f) }
    var start by remember { mutableFloatStateOf(1f) }
    var delta by remember { mutableFloatStateOf(0f) }
    fun settle(open: Boolean) {
        onSettled(open)
        scope.launch { progress.animateTo(if (open) 1f else 0f, tween(settings.motionDuration(400))) }
    }
    val currentSettle by rememberUpdatedState<(Boolean) -> Unit>(::settle)
    val dismissDistance = with(density) { 72.dp.toPx() }
    val dismissVelocity = with(density) { 300.dp.toPx() }
    val listPull = remember(progress, distance, dismissDistance, dismissVelocity) {
        object : NestedScrollConnection {
            var pulling = false
            var pulled = 0f
            fun move(dy: Float): Offset {
                pulled = (pulled + dy).coerceIn(0f, distance)
                scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo(1f - pulled / distance) }
                return Offset(0f, dy)
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
                currentSettle(!(pulled >= dismissDistance || available.y >= dismissVelocity))
                pulled = 0f
                return Velocity(0f, available.y)
            }
        }
    }
    val p = progress.value
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = p * 0.2f }.clickable { settle(false) }) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.scrim) {}
    }
    Surface(Modifier.offset(x = 8.dp * (1 - p), y = top + (status - top) * p)
        .width(maxWidth - 16.dp * (1 - p)).height(64.dp + (maxHeight - status - inset - 64.dp) * p)
        .draggable(rememberDraggableState { dy ->
            delta += dy
            scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo((start - delta / distance).coerceIn(0f, 1f)) }
        }, Orientation.Vertical, onDragStarted = { progress.stop(); start = progress.value; delta = 0f },
            onDragStopped = { velocity -> settle(if (velocity > with(density) { 300.dp.toPx() }) false else progress.value >= 0.5f) })
        .nestedScroll(listPull).testTag("queue-surface"), shape = Corners.panel, tonalElevation = 8.dp) {
        Column(Modifier.fillMaxSize().clipToBounds()) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Queue", Modifier.weight(1f).padding(start = 16.dp), style = MaterialTheme.typography.titleMedium)
                ActionIcon(R.drawable.ic_down, "Close queue") { settle(false) }
            }
            content()
        }
    }
}
