package io.github.anastaci1a.localica.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize

/** Only a touch-down on the visible thumb's target ([hit]) can belong to the scrollbar; other touches reach the
 * list. Over a shared button, [claim] resolves travel and time (null pending, true bar, false button) and nothing
 * is consumed until the bar wins, so the button keeps taps and holds (#950); otherwise the bar owns it at once.
 * An owned contact is [active] and captured exclusively until release; each finger pixel is a [move] delta.
 */
internal fun Modifier.scrollbarInput(key: Any, hit: (Float) -> Boolean, claim: (Offset) -> ((Offset, Long) -> Boolean?)?,
    active: (Boolean) -> Unit, move: (Float) -> Unit): Modifier = this
            .pointerInput(key) {
                // The strip shares input with the list beneath, so contact is claimed on the Initial pass.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (!hit(down.position.y)) return@awaitEachGesture
                    val pending = claim(down.position)
                    var owned = pending == null
                    if (owned) { down.consume(); active(true) }
                    var travel = Offset.Zero
                    try {
                        while (true) {
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed || !change.pressed) break
                            var dy = change.position.y - change.previousPosition.y
                            if (!owned) {
                                travel += change.position - change.previousPosition
                                when (pending!!(travel, change.uptimeMillis - down.uptimeMillis)) {
                                    null -> continue
                                    false -> break
                                    true -> { owned = true; active(true); dy = travel.y }
                                }
                            }
                            change.consume()
                            move(dy)
                        }
                    } finally { if (owned) active(false) }
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
