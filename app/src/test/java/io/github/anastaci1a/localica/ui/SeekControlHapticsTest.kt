package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.interaction.DragInteraction
import org.junit.Assert.assertEquals
import org.junit.Test

class SeekControlHapticsTest {
    private val pulses = mutableListOf<ControlHaptic>()
    private val feedback = SeekControlHaptics(ControlHaptics { pulses += it })

    @Test fun genuineDragHasOneEngageAndReleaseDespiteDuplicateFinishes() {
        feedback.beginTouch()
        val start = DragInteraction.Start()
        feedback.interaction(start)
        repeat(3) { feedback.valueChange() }
        feedback.pointer(false, genuineUp = true, cancelled = false)
        feedback.interaction(DragInteraction.Stop(start))
        repeat(2) { feedback.materialFinish(); feedback.interaction(DragInteraction.Stop(start)) }
        assertEquals(listOf(ControlHaptic.Weak, ControlHaptic.Strong), pulses)
    }

    @Test fun tapAndIndependentAccessibleEditEachHaveOneWeak() {
        feedback.beginTouch()
        feedback.pointer(false, genuineUp = true, cancelled = false)
        feedback.valueChange()
        repeat(2) { feedback.materialFinish() }
        feedback.nonTouchInput(); feedback.activation()
        assertEquals(List(2) { ControlHaptic.Weak }, pulses)
    }

    @Test fun xHoverTransitionsDeduplicateAndReleaseOnXHasNoReleasePulse() {
        feedback.beginTouch()
        val start = DragInteraction.Start()
        feedback.interaction(start)
        repeat(2) { feedback.pointer(true, false, false) }
        feedback.pointer(false, false, false)
        feedback.pointer(true, false, false)
        feedback.pointer(true, true, true)
        feedback.interaction(DragInteraction.Stop(start)); feedback.materialFinish()
        assertEquals(listOf(ControlHaptic.Weak, ControlHaptic.Strong, ControlHaptic.Weak, ControlHaptic.Strong), pulses)
    }

    @Test fun genuineUpEnteringXRetainsOnlyItsFirmHoverEdge() {
        feedback.beginTouch()
        val start = DragInteraction.Start()
        feedback.interaction(start)
        feedback.pointer(true, true, true)
        feedback.interaction(DragInteraction.Stop(start)); feedback.materialFinish()
        assertEquals(listOf(ControlHaptic.Weak, ControlHaptic.Strong), pulses)
    }

    @Test fun pendingCancelledAndLateStartNeverEngage() {
        feedback.beginTouch()
        feedback.pointer(true, false, false)
        feedback.cancelTouch()
        val start = DragInteraction.Start()
        feedback.interaction(start); feedback.interaction(DragInteraction.Cancel(start)); feedback.materialFinish()
        assertEquals(emptyList<ControlHaptic>(), pulses)
    }

    @Test fun consumedUpAndMaterialCancelNeverProduceSuccessfulRelease() {
        feedback.beginTouch()
        val start = DragInteraction.Start()
        feedback.interaction(start)
        feedback.pointer(false, false, true)
        feedback.interaction(DragInteraction.Cancel(start)); feedback.materialFinish()
        assertEquals(listOf(ControlHaptic.Weak), pulses)
    }

    @Test fun delayedOldStopCannotReleaseANewerContact() {
        feedback.beginTouch()
        val old = DragInteraction.Start()
        feedback.interaction(old)
        feedback.pointer(false, true, false)
        feedback.beginTouch()
        feedback.interaction(DragInteraction.Stop(old))
        feedback.materialFinish() // Old Material callback cannot terminally cancel the new down.
        val next = DragInteraction.Start()
        feedback.interaction(next)
        feedback.pointer(false, true, false)
        feedback.interaction(DragInteraction.Stop(next)); feedback.materialFinish()
        assertEquals(listOf(ControlHaptic.Weak, ControlHaptic.Weak, ControlHaptic.Strong), pulses)
    }

    @Test fun lateFinishOutsideTapDispatchWindowCannotPulseANewerContact() {
        feedback.beginTouch()
        feedback.pointer(false, true, false)
        feedback.pointerFinal()
        feedback.materialFinish()
        assertEquals(emptyList<ControlHaptic>(), pulses)
    }

    @Test fun startDeliveredAfterGenuineUpStillUsesThatContactsPermission() {
        feedback.beginTouch()
        feedback.pointer(false, true, false)
        feedback.pointerFinal()
        val start = DragInteraction.Start()
        feedback.interaction(start); feedback.valueChange()
        feedback.interaction(DragInteraction.Stop(start)); feedback.materialFinish()
        assertEquals(listOf(ControlHaptic.Weak, ControlHaptic.Strong), pulses)
    }
}
