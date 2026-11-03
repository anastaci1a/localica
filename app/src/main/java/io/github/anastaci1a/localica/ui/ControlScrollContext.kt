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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/** One ancestor that can move under a finger, linked to the ancestors above it. */
internal class ControlScrollNode(private val available: () -> Int) {
    var parent: ControlScrollNode? = null
    fun directions(): Int = available() or (parent?.directions() ?: ControlScrollDirection.None)
}

private val LocalControlScrollNode = modifierLocalOf<ControlScrollNode?> { null }

/** Read when a contact begins, so a parent reaching its edge cannot steal an already
 * resolved gesture. No composition observes per-frame pointer data. */
internal class ControlScrollContext {
    internal var node: ControlScrollNode? = null
    /** Union of the finger directions every ancestor can still move, read live. */
    fun directions(): Int = node?.directions() ?: ControlScrollDirection.None
}

@Composable internal fun rememberControlScrollContext() = remember { ControlScrollContext() }

internal fun Modifier.readControlScrollContext(context: ControlScrollContext): Modifier =
    modifierLocalConsumer { context.node = LocalControlScrollNode.current }

/** Advertise finger directions a surface can move without replacing its own gesture.
 * Drawers and the player register here exactly like scroll containers. */
internal fun Modifier.controlScrollParent(directions: () -> Int): Modifier = composed {
    val current by rememberUpdatedState(directions)
    val node = remember { ControlScrollNode { current() } }
    Modifier.modifierLocalConsumer { node.parent = LocalControlScrollNode.current }
        .modifierLocalProvider(LocalControlScrollNode) { node }
}

private fun Modifier.controlScrollParent(state: ScrollableState, vertical: Boolean, reverse: Boolean,
    enabled: () -> Boolean): Modifier = composed {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // Foundation mirrors horizontal scrolling in right-to-left layouts.
    val reversed = reverse != (!vertical && rtl)
    Modifier.controlScrollParent {
        if (!enabled()) ControlScrollDirection.None
        else controlScrollDirections(vertical, state.canScrollForward, state.canScrollBackward, reversed)
    }
}

internal fun Modifier.controlScrollParent(state: ScrollableState, axis: Int): Modifier =
    (if ((axis and ControlScrollAxis.Vertical) != 0) popupScrollEdges(state) else this)
        .modalScroll(state)
        .controlScrollParent(state, vertical = (axis and ControlScrollAxis.Vertical) != 0, reverse = false) { true }

internal fun Modifier.controlVerticalScroll(state: ScrollState, enabled: Boolean = true,
    reverseScrolling: Boolean = false): Modifier =
    popupScrollEdges(state, reverseScrolling)
        .modalScroll(state)
        .controlScrollParent(state, vertical = true, reverse = reverseScrolling) { enabled }
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
    modalScroll(state).controlScrollParent(state, vertical = false, reverse = reverseScrolling) { enabled }
        .horizontalScroll(state, enabled, reverseScrolling = reverseScrolling)
