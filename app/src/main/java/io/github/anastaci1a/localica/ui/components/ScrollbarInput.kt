package io.github.anastaci1a.localica.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize

/** Only a touch-down on the visible thumb's target ([hit]) belongs to the scrollbar; every other touch
 * on the strip stays unconsumed for the list and its rows. An accepted contact is selected at once
 * ([press]) and captured exclusively until release: the list beneath sees a consumed stream, so it never
 * scrolls, presses, long-presses or taps. Past touch slop the finger's travel beyond the slop drives the
 * drag as relative deltas ([move]); releasing without a drag only deselects.
 */
internal fun Modifier.scrollbarInput(key: Any, hit: (Float) -> Boolean, press: (Boolean) -> Unit,
    begin: () -> Unit, move: (Float) -> Unit, end: () -> Unit): Modifier = this
            .pointerInput(key) {
                // The strip shares input with the list beneath, so contact is claimed on the Initial pass.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (!hit(down.position.y)) return@awaitEachGesture
                    down.consume()
                    press(true)
                    var travel = 0f
                    var dragging = false
                    try {
                        while (true) {
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed) break
                            change.consume()
                            if (!change.pressed) break
                            val dy = change.position.y - change.previousPosition.y
                            if (dragging) { move(dy); continue }
                            travel += dy
                            val beyond = kotlin.math.abs(travel) - viewConfiguration.touchSlop
                            if (beyond < 0f) continue
                            dragging = true
                            begin()
                            move(if (travel > 0f) beyond else -beyond)
                        }
                    } finally {
                        if (dragging) end()
                        press(false)
                    }
                }
            }

/** The edge strip takes the thumb's contacts, while every other touch still reaches the list and its rows. */
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
