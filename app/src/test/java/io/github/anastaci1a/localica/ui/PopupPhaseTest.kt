package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.ui.PopupPresenter.*
import io.github.anastaci1a.localica.ui.PopupTakeover.*
import org.junit.Assert.assertEquals
import org.junit.Test

/** #920 step 1: the derived phase names every model state and refuses every flag vector outside the model. */
class PopupPhaseTest {
    private val closed = PopupFlags(present = false, hidden = false, modalPresent = false, shadowInModal = false,
        contactModal = false, preparedOrigin = false, originAdmitted = false, windowAdmitted = false, shown = false,
        homeDeparting = false, homeGone = false, offered = false, taken = false, projected = false, expansion = 0f)
    private val inWindow = closed.copy(present = true, hidden = true, modalPresent = true, shadowInModal = true,
        contactModal = true, windowAdmitted = true, shown = true, expansion = .5f)
    private val restoring = inWindow.copy(hidden = false, shown = false, expansion = 0f)

    private fun phase(flags: PopupFlags) = popupPhaseOf(flags)
    private fun rule(flags: PopupFlags) = (popupPhaseOf(flags) as IllegalPopupState).rule

    @Test fun everyModelStateIsNamed() {
        assertEquals(PopupPhase(Closed), phase(closed))
        assertEquals(PopupPhase(Closed), phase(closed.copy(present = true, homeGone = true)))
        assertEquals(PopupPhase(Admitting), phase(closed.copy(present = true, shown = true)))
        assertEquals(PopupPhase(InWindow, Window), phase(inWindow))
        assertEquals(PopupPhase(InWindow, Window), phase(inWindow.copy(shown = false)))
        assertEquals(PopupPhase(InWindow, Offered), phase(inWindow.copy(homeDeparting = true, offered = true)))
        assertEquals(PopupPhase(InWindow, Taken), phase(inWindow.copy(homeDeparting = true, offered = true, taken = true, projected = true)))
        assertEquals(PopupPhase(InWindow, Taken), phase(inWindow.copy(shown = false, offered = true, taken = true, projected = true)))
        assertEquals(PopupPhase(InWindow, Releasing), phase(inWindow.copy(projected = true)))
        assertEquals(PopupPhase(Restoring, Window), phase(restoring))
        assertEquals(PopupPhase(InWindow, Window), phase(inWindow.copy(preparedOrigin = true, originAdmitted = true)))
    }

    @Test fun stepZeroTailFindings() {
        // F1 (ADDENDUM-TAIL 1.2): Restoring with the home presenting, at the trigger.
        assertEquals(PopupPhase(Restoring, Taken), phase(restoring.copy(offered = true, taken = true, projected = true)))
        // F2 (ADDENDUM-TAIL 1.3): Closed has no presenter, so a projection left after close is refused.
        assertEquals("presenter without takeover", rule(closed.copy(offered = true, taken = true, projected = true)))
    }

    @Test fun transitionTable() {
        fun edge(a: PopupTakeover, p: PopupPresenter?, b: PopupTakeover, q: PopupPresenter?) =
            legalPopupEdge(PopupPhase(a, p), PopupPhase(b, q))
        assertEquals(true, edge(Closed, null, Admitting, null))
        assertEquals(true, edge(Admitting, null, InWindow, Window))
        assertEquals(false, edge(Admitting, null, InWindow, Taken))
        assertEquals(false, edge(Closed, null, InWindow, Window))
        assertEquals(true, edge(InWindow, Taken, Restoring, Taken)) // F1's entry
        assertEquals(false, edge(InWindow, Window, Restoring, Taken))
        assertEquals(true, edge(Restoring, Taken, InWindow, Releasing))
        assertEquals(false, edge(Restoring, Taken, InWindow, Window)) // #925: leaving a handoff needs the home's receipt
        assertEquals(true, edge(InWindow, Releasing, InWindow, Window)) // OfferReleased
        assertEquals(false, edge(InWindow, Taken, InWindow, Window))
        assertEquals(true, edge(Restoring, Window, Closed, null))
        assertEquals(false, edge(Restoring, Window, Admitting, null))
    }

    @Test fun everyRuleRefusesItsState() {
        assertEquals("split ownership", rule(inWindow.copy(shadowInModal = false)))
        assertEquals("split ownership", rule(inWindow.copy(preparedOrigin = true)))
        assertEquals("taken without offer", rule(inWindow.copy(taken = true)))
        assertEquals("takeover without a window", rule(inWindow.copy(present = false)))
        assertEquals("takeover without a window", rule(inWindow.copy(homeGone = true)))
        assertEquals("takeover without its admitted window", rule(inWindow.copy(windowAdmitted = false)))
        assertEquals("hidden trigger without takeover", rule(closed.copy(present = true, hidden = true, shown = true)))
        assertEquals("window outlives its takeover", rule(closed.copy(present = true)))
        assertEquals("presenter without takeover", rule(closed.copy(present = true, shown = true, projected = true)))
        assertEquals("admitting window shows the popup", rule(closed.copy(present = true, shown = true, expansion = .1f)))
        assertEquals("restoring with intent shown", rule(restoring.copy(shown = true)))
        assertEquals("restoring away from the trigger", rule(restoring.copy(expansion = .01f)))
        assertEquals("offer without a departing home", rule(inWindow.copy(offered = true)))
        assertEquals("taken handoff not released on reopen", rule(inWindow.copy(offered = true, taken = true)))
    }
}
