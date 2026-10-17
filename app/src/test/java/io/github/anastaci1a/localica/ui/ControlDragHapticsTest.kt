package io.github.anastaci1a.localica.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ControlDragHapticsTest {
    private val pulses = mutableListOf<ControlHaptic>()
    private val drag = ControlDragHaptics(ControlHaptics { pulses += it })

    @Test fun ownershipEmitsOnceAndInitialOptionDoesNotAlsoTick() {
        drag.engage(1); drag.engage(1); drag.move(1); drag.move(1)
        assertEquals(listOf(ControlHaptic.Weak), pulses)
    }

    @Test fun distinctTransitionsIncludingBacktrackingEachTickOnce() {
        drag.engage(0); drag.move(1); drag.move(1); drag.move(2); drag.move(1)
        drag.finish(true); drag.finish(true); drag.finish(false)
        assertEquals(List(4) { ControlHaptic.Weak } + ControlHaptic.Strong, pulses)
    }

    @Test fun cancellationCannotBecomeAReleaseOrAcceptLaterMoves() {
        drag.engage(0); drag.move(1); drag.finish(false); drag.finish(true); drag.move(2)
        assertEquals(List(2) { ControlHaptic.Weak }, pulses)
    }

    @Test fun unownedMovementAndFinishAreSilent() {
        drag.move(1); drag.finish(true); drag.finish(false)
        assertEquals(emptyList<ControlHaptic>(), pulses)
    }

    @Test fun freshGestureAfterCancellationHasIndependentLifecycle() {
        drag.engage(0); drag.finish(false); drag.engage(2); drag.move(2); drag.finish(true)
        assertEquals(listOf(ControlHaptic.Weak, ControlHaptic.Weak, ControlHaptic.Strong), pulses)
    }
}
