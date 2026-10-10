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
        .testTag("queue-surface"), shape = Corners.panel, tonalElevation = 8.dp) {
        Column(Modifier.fillMaxSize().clipToBounds()) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Queue", Modifier.weight(1f).padding(start = 16.dp), style = MaterialTheme.typography.titleMedium)
                ActionIcon(R.drawable.ic_down, "Close queue") { settle(false) }
            }
            content()
        }
    }
}
