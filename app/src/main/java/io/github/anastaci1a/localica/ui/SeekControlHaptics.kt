package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource

/** Feedback follows Material's actual drag owner, not contact or playback state. */
internal class SeekControlHaptics(private val sink: ControlHaptics) {
    private class Contact(sink: ControlHaptics) {
        val drag = ControlDragHaptics(sink)
        var owned = false
        var over = false
        var reportedOver = false
        var genuineUp = false
        var tapFinishWindow = false
        var finished = false
    }
    private var contact: Contact? = null
    private val owners = mutableMapOf<DragInteraction.Start, Contact>()

    fun beginTouch() {
        contact?.let { finish(it, false) }
        contact = Contact(sink)
    }

    fun pointer(over: Boolean, genuineUp: Boolean, cancelled: Boolean) {
        val current = contact ?: return
        current.over = over
        current.genuineUp = genuineUp
        current.tapFinishWindow = genuineUp && !cancelled
        // A genuine up may itself enter X: retain that hover edge, never a release pulse.
        // A consumed synthetic up must not manufacture a new hover transition.
        if (!cancelled || genuineUp) hover(current)
        if (cancelled) finish(current, false)
    }

    // Material's tap callback runs synchronously in Main between the observed up and Final.
    fun pointerFinal() { contact?.tapFinishWindow = false }

    fun cancelTouch() { contact?.let { finish(it, false) } }

    fun interaction(event: Interaction) {
        when (event) {
            is DragInteraction.Start -> contact?.takeUnless { it.finished }?.let {
                owners[event] = it
                it.owned = true
                it.drag.engage(0)
                hover(it)
            }
            is DragInteraction.Stop -> owners.remove(event.start)?.let {
                finish(it, it.genuineUp && !it.over)
            }
            is DragInteraction.Cancel -> owners.remove(event.start)?.let { finish(it, false) }
        }
    }

    /** Material also finishes cancelled drags; only a genuine unowned tap pulses here. */
    fun materialFinish() {
        val current = contact ?: return
        // An old drag's trailing Material callback may arrive during a new contact.
        // Stop/Cancel owns drag termination; this callback may finish only a valid tap.
        if (!current.finished && !current.owned && current.tapFinishWindow && current.genuineUp && !current.over) {
            sink.emit(ControlHaptic.Weak)
            finish(current, false)
        }
    }

    fun nonTouchInput() {
        cancelTouch()
        contact = null
    }

    fun activation() { sink.emit(ControlHaptic.Weak) }

    // A delayed Material value callback after pointer Final still belongs to that touch.
    fun valueChange() { if (contact == null) activation() }

    private fun hover(current: Contact) {
        if (!current.owned || current.finished || current.over == current.reportedOver) return
        current.reportedOver = current.over
        sink.emit(if (current.over) ControlHaptic.Strong else ControlHaptic.Weak)
    }

    private fun finish(current: Contact, released: Boolean) {
        if (current.finished) return
        current.finished = true
        current.drag.finish(released)
    }
}

/** Observe synchronously: a flow collector can run after Slider's finish callback. */
internal class SeekInteractionSource(
    private val source: MutableInteractionSource,
    private val feedback: SeekControlHaptics,
) : MutableInteractionSource {
    override val interactions = source.interactions
    override suspend fun emit(interaction: Interaction) {
        feedback.interaction(interaction)
        source.emit(interaction)
    }
    override fun tryEmit(interaction: Interaction): Boolean {
        feedback.interaction(interaction)
        return source.tryEmit(interaction)
    }
}
