package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R

@Composable
internal fun ReorderHandle(label: String, onMove: (Int) -> Unit) {
    val move by rememberUpdatedState(onMove)
    val step = with(LocalDensity.current) { 64.dp.toPx() }
    androidx.compose.foundation.layout.Box(Modifier.size(48.dp).semantics {
        contentDescription = label
        customActions = listOf(CustomAccessibilityAction("Move up") { move(-1); true },
            CustomAccessibilityAction("Move down") { move(1); true })
    }.pointerInput(step) {
        var distance = 0f
        detectVerticalDragGestures(onDragStart = { distance = 0f }, onVerticalDrag = { change, delta ->
            change.consume(); distance += delta
            while (kotlin.math.abs(distance) >= step) {
                val offset = if (distance > 0) 1 else -1
                move(offset); distance -= step * offset
            }
        })
    }, contentAlignment = androidx.compose.ui.Alignment.Center) {
        Icon(painterResource(R.drawable.ic_drag), null, Modifier.size(24.dp))
    }
}
