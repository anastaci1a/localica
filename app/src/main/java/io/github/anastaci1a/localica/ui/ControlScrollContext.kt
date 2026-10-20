package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.modifier.modifierLocalConsumer
import androidx.compose.ui.modifier.modifierLocalOf
import androidx.compose.ui.modifier.modifierLocalProvider

private val LocalControlScrollAxes = modifierLocalOf { ControlScrollAxis.None }

/** Read once when a contact begins, so a parent reaching its edge cannot steal
 * an already resolved gesture. No composition observes per-frame pointer data. */
internal class ControlScrollContext { var axes: Int = ControlScrollAxis.None }

@Composable internal fun rememberControlScrollContext() = remember { ControlScrollContext() }

internal fun Modifier.readControlScrollContext(context: ControlScrollContext): Modifier =
    modifierLocalConsumer { context.axes = LocalControlScrollAxes.current }

/** Advertise available scroll axes without replacing Foundation's scrolling. */
internal fun Modifier.controlScrollParent(axis: Int, available: () -> Boolean): Modifier = composed {
    var inherited by remember { mutableIntStateOf(ControlScrollAxis.None) }
    val axes = inherited or if (available()) axis else ControlScrollAxis.None
    Modifier.modifierLocalConsumer { inherited = LocalControlScrollAxes.current }
        .modifierLocalProvider(LocalControlScrollAxes) { axes }
}

internal fun Modifier.controlScrollParent(state: ScrollableState, axis: Int): Modifier =
    (if ((axis and ControlScrollAxis.Vertical) != 0) popupScrollEdges(state) else this)
        .modalScroll(state)
        .controlScrollParent(axis) { state.canScrollBackward || state.canScrollForward }

internal fun Modifier.controlVerticalScroll(state: ScrollState, enabled: Boolean = true,
    reverseScrolling: Boolean = false): Modifier =
    popupScrollEdges(state, reverseScrolling)
        .modalScroll(state)
        .controlScrollParent(ControlScrollAxis.Vertical) { enabled && (state.canScrollBackward || state.canScrollForward) }
        .verticalScroll(state, enabled, reverseScrolling = reverseScrolling)

private fun Modifier.popupScrollEdges(state: ScrollableState, reverseScrolling: Boolean = false): Modifier {
    if (hasScrollEdgeFade()) return this
    return composed {
        if (!LocalPopupScrollEdges.current) Modifier
        else when (state) {
            is ScrollState -> Modifier.scrollEdgeFade(state, reverseScrolling)
            is LazyListState -> Modifier.scrollEdgeFade(state)
            is LazyGridState -> Modifier.scrollEdgeFade(state)
            else -> Modifier
        }
    }
}

internal fun Modifier.controlHorizontalScroll(state: ScrollState, enabled: Boolean = true,
    reverseScrolling: Boolean = false): Modifier =
    modalScroll(state).controlScrollParent(ControlScrollAxis.Horizontal) { enabled && (state.canScrollBackward || state.canScrollForward) }
        .horizontalScroll(state, enabled, reverseScrolling = reverseScrolling)
