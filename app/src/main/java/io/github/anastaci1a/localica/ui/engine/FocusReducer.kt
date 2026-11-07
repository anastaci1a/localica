package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset

/**
 * Timing and admission numbers for one list, resolved once (ms and px). [liftMs] is the system touch-and-hold
 * delay (`ViewConfiguration.longPressTimeout`, operator answer H2); [deadZoneMs] is `FocusSpec.deadZoneMs`;
 * [slopPx] is the touch slop, used both for the scroll yield before the lift and for the card latch after it.
 */
@Immutable data class FocusTiming(val deadZoneMs: Long, val liftMs: Long, val slopPx: Float) {
    init { require(deadZoneMs >= 0 && liftMs > deadZoneMs && slopPx > 0f && slopPx.isFinite()) { "0 <= dead zone < lift, slop > 0" } }
}

/** Input to [FocusReducer]; every event carries the input time [t] in ms so the reducer stays pure. */
sealed interface FocusEvent {
    val t: Long
    /** First pointer down. [key] is the hit item or null; [admitted] is false while a query is pending, a pinch owns the list or a drag is dropping. */
    data class Down(override val t: Long, val key: Any?, val position: Offset, val mutable: Boolean, val admitted: Boolean = true, val flinging: Boolean = false) : FocusEvent
    data class Move(override val t: Long, val position: Offset, val pointers: Int = 1) : FocusEvent
    data class Up(override val t: Long, val position: Offset) : FocusEvent
    /** Pointer cancel, or the list became disabled during the gesture. */
    data class Cancel(override val t: Long) : FocusEvent
    /** Time only; the owner sends one per frame while a press is waiting for its lift point. */
    data class Tick(override val t: Long) : FocusEvent
    /** The keyed drag dropped or cancelled on its own. */
    data class DragEnded(override val t: Long) : FocusEvent
    /** The card presenter drew its first frame. */
    data class CardPresented(override val t: Long) : FocusEvent
    /** Outside tap, Back, swipe-down, an option chosen or a configuration change. */
    data class Dismiss(override val t: Long) : FocusEvent
    /** The lift, follow and blur channels are at rest. */
    data class Settled(override val t: Long) : FocusEvent
}

/** Side effects the owner performs; the reducer never performs them itself. */
sealed interface FocusEffect {
    /** `ControlHaptic.Lift`, exactly once per lifted gesture. */
    data object Haptic : FocusEffect
    data class Tap(val key: Any) : FocusEffect
    /** Let the list scroll own the gesture; nothing was consumed. */
    data object YieldScroll : FocusEffect
    /** `KeyedDragState.begin(item, keys, down)` with the lifted geometry. */
    data class BeginDrag(val key: Any, val down: Offset) : FocusEffect
    data class OpenCard(val key: Any) : FocusEffect
    data object CloseCard : FocusEffect
    /** Retarget lift, follow and blur to rest from their current kinematics (I1). */
    data object Settle : FocusEffect
}

sealed interface FocusState {
    data object Idle : FocusState
    /** Channels easing back to rest; a new down is admitted and retargets from the current value. */
    data object Settling : FocusState
    sealed interface Held : FocusState { val key: Any; val down: Offset; val t0: Long; val mutable: Boolean }
    data class Pressing(override val key: Any, override val down: Offset, override val t0: Long, override val mutable: Boolean) : Held
    /** Past the lift point; the card is still armed while the pointer has stayed within slop. */
    data class Lifted(override val key: Any, override val down: Offset, override val t0: Long, override val mutable: Boolean, val position: Offset) : Held
    /** Immutable list: slop exceeded after the lift, so the card is latched off and the item follows logarithmically. */
    data class Following(override val key: Any, override val down: Offset, override val t0: Long, val position: Offset) : Held { override val mutable: Boolean get() = false }
    data class Dragging(val key: Any) : FocusState
    sealed interface Card : FocusState { val key: Any }
    data class CardOpening(override val key: Any) : Card
    data class CardOpen(override val key: Any) : Card
    data class CardClosing(override val key: Any) : Card

    /** True only after the lift, while the pointer is still down: the only time the adapter consumes changes. */
    val ownsPointer: Boolean get() = this is Lifted || this is Following || this is Dragging
}

/**
 * Pure state machine of DESIGN.md §1.1 (#640). `reduce(state, event)` appends effects and returns the next state.
 * The lift point is time-driven: an event stamped at or after `t0 + liftMs` lifts a press before the event itself
 * applies, so event order and input times decide every outcome. One gesture ends in exactly one outcome.
 */
class FocusReducer(val timing: FocusTiming) {
    /** Target of the hold-progress channel h: 0 before the dead zone, linear to 1 at the lift point, 1 while held or carded. */
    fun holdTarget(state: FocusState, now: Long): Float = when (state) {
        is FocusState.Pressing -> {
            val ramp = (timing.liftMs - timing.deadZoneMs).toFloat()
            val elapsed = (now - state.t0 - timing.deadZoneMs).toFloat()
            if (elapsed <= 0f) 0f else if (elapsed >= ramp) 1f else elapsed / ramp
        }
        is FocusState.Lifted, is FocusState.Following, is FocusState.Dragging, is FocusState.Card -> 1f
        FocusState.Idle, FocusState.Settling -> 0f
    }

    /** Input time at which [state] lifts, or null when nothing is pressed. */
    fun liftAt(state: FocusState): Long? = (state as? FocusState.Held)?.let { it.t0 + timing.liftMs }

    /** The time after which the press has visible hold progress and the blur capture and card pre-composition start. */
    fun deadZoneEnd(state: FocusState): Long? = (state as? FocusState.Pressing)?.let { it.t0 + timing.deadZoneMs }

    fun reduce(state: FocusState, event: FocusEvent, effects: MutableList<FocusEffect>): FocusState {
        var s = state
        if (s is FocusState.Pressing && event.t >= s.t0 + timing.liftMs) {
            effects += FocusEffect.Haptic
            s = FocusState.Lifted(s.key, s.down, s.t0, s.mutable, s.down)
        }
        return when (s) {
            FocusState.Idle, FocusState.Settling -> when (event) {
                is FocusEvent.Down -> if (event.admitted && !event.flinging && event.key != null) FocusState.Pressing(event.key, event.position, event.t, event.mutable) else s
                is FocusEvent.Settled -> FocusState.Idle
                else -> s
            }
            is FocusState.Pressing -> when (event) {
                is FocusEvent.Move -> when {
                    event.pointers > 1 -> settle(effects)
                    beyondSlop(event.position, s.down) -> { effects += FocusEffect.YieldScroll; settle(effects) }
                    else -> s
                }
                is FocusEvent.Up -> { effects += FocusEffect.Tap(s.key); settle(effects) }
                is FocusEvent.Cancel, is FocusEvent.Down -> settle(effects)
                is FocusEvent.Tick, is FocusEvent.DragEnded, is FocusEvent.CardPresented, is FocusEvent.Dismiss, is FocusEvent.Settled -> s
            }
            is FocusState.Lifted -> when (event) {
                is FocusEvent.Move -> if (!beyondSlop(event.position, s.down)) s.copy(position = event.position)
                    else if (s.mutable) { effects += FocusEffect.BeginDrag(s.key, s.down); FocusState.Dragging(s.key) }
                    else FocusState.Following(s.key, s.down, s.t0, event.position)
                is FocusEvent.Up -> if (beyondSlop(event.position, s.down)) settle(effects) else { effects += FocusEffect.OpenCard(s.key); FocusState.CardOpening(s.key) }
                is FocusEvent.Cancel -> settle(effects)
                is FocusEvent.Down, is FocusEvent.Tick, is FocusEvent.DragEnded, is FocusEvent.CardPresented, is FocusEvent.Dismiss, is FocusEvent.Settled -> s
            }
            is FocusState.Following -> when (event) {
                is FocusEvent.Move -> s.copy(position = event.position)
                is FocusEvent.Up, is FocusEvent.Cancel -> settle(effects)
                is FocusEvent.Down, is FocusEvent.Tick, is FocusEvent.DragEnded, is FocusEvent.CardPresented, is FocusEvent.Dismiss, is FocusEvent.Settled -> s
            }
            is FocusState.Dragging -> when (event) {
                is FocusEvent.Up, is FocusEvent.Cancel, is FocusEvent.DragEnded -> settle(effects)
                is FocusEvent.Down, is FocusEvent.Move, is FocusEvent.Tick, is FocusEvent.CardPresented, is FocusEvent.Dismiss, is FocusEvent.Settled -> s
            }
            is FocusState.CardOpening -> when (event) {
                is FocusEvent.CardPresented -> FocusState.CardOpen(s.key)
                is FocusEvent.Dismiss -> { effects += FocusEffect.CloseCard; FocusState.CardClosing(s.key) }
                is FocusEvent.Down -> { effects += FocusEffect.CloseCard; settle(effects) }
                is FocusEvent.Move, is FocusEvent.Up, is FocusEvent.Cancel, is FocusEvent.Tick, is FocusEvent.DragEnded, is FocusEvent.Settled -> s
            }
            is FocusState.CardOpen -> when (event) {
                is FocusEvent.Dismiss -> { effects += FocusEffect.CloseCard; FocusState.CardClosing(s.key) }
                is FocusEvent.Down, is FocusEvent.Move, is FocusEvent.Up, is FocusEvent.Cancel, is FocusEvent.Tick, is FocusEvent.DragEnded, is FocusEvent.CardPresented, is FocusEvent.Settled -> s
            }
            is FocusState.CardClosing -> when (event) {
                is FocusEvent.Settled -> FocusState.Idle
                is FocusEvent.Down -> if (event.admitted && !event.flinging && event.key != null) FocusState.Pressing(event.key, event.position, event.t, event.mutable) else s
                is FocusEvent.Move, is FocusEvent.Up, is FocusEvent.Cancel, is FocusEvent.Tick, is FocusEvent.DragEnded, is FocusEvent.CardPresented, is FocusEvent.Dismiss -> s
            }
        }
    }

    private fun beyondSlop(position: Offset, down: Offset): Boolean = (position - down).getDistance() > timing.slopPx

    private fun settle(effects: MutableList<FocusEffect>): FocusState { effects += FocusEffect.Settle; return FocusState.Settling }
}
