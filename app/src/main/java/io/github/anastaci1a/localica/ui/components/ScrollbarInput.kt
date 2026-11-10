package io.github.anastaci1a.localica.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize

/** A rail shares row taps and owns a vertical drag only after crossing touch slop. */
internal fun Modifier.scrollbarInput(key: Any, dragging: () -> Boolean,
    press: (Boolean) -> Unit, activity: () -> Unit, top: () -> Float, thumb: () -> Float,
    grab: (Float) -> Unit, begin: () -> Unit, seek: (Float) -> Unit, dragY: () -> Float,
    endDrag: () -> Unit): Modifier = this
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    activity()
                    press(true)
                    try {
                        do { val event = awaitPointerEvent(PointerEventPass.Initial) } while (event.changes.any { it.pressed })
                    } finally { press(false) }
                }
            }
            .pointerInput(key) {
                // The strip shares input with the list beneath, so the drag is claimed on the Initial pass:
                // the list's own Main-pass scroll then sees a consumed stream, while taps stay unconsumed for row actions.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var travel = 0f
                    try {
                        while (true) {
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val dy = change.position.y - change.previousPosition.y
                            if (!dragging()) {
                                if (change.isConsumed) break
                                travel += dy
                                if (kotlin.math.abs(travel) < viewConfiguration.touchSlop) continue
                                // Grabbing the thumb keeps its offset under the finger; elsewhere the thumb comes to the finger.
                                val top = top()
                                grab(if (down.position.y in top..(top + thumb())) down.position.y - (top + thumb() / 2) else 0f)
                                begin(); seek(down.position.y + travel)
                            } else seek(dragY() + dy)
                            change.consume()
                        }
                    } finally {
                        endDrag()
                    }
                }
            }

/** The edge strip takes drags, while unconsumed taps still reach a row's trailing action. */
internal object ScrollbarTapSharing : ModifierNodeElement<ScrollbarTapSharingNode>() {
    override fun create() = ScrollbarTapSharingNode()
    override fun update(node: ScrollbarTapSharingNode) = Unit
    override fun equals(other: Any?) = this === other
    override fun hashCode() = javaClass.hashCode()
    override fun InspectorInfo.inspectableProperties() { name = "scrollbarTapSharing" }
}

internal class ScrollbarTapSharingNode : Modifier.Node(), PointerInputModifierNode {
    override fun sharePointerInputWithSiblings() = true
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) = Unit
    override fun onCancelPointerInput() = Unit
}
