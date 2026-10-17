package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test

class ScrollGestureOwnershipTest {
    @Test fun drawerClaimCannotBecomeContentOwnershipUntilNewDown() {
        val owner = ScrollGestureOwnership()
        owner.begin(); owner.claimDrawer(); owner.recordScroll(-80f)
        assertTrue(owner.drawerOwned)
        assertFalse(owner.contentOwned)
        assertFalse(owner.mayHandoff)
        owner.release(); owner.end()
        assertTrue(owner.mayCommitPull)
        owner.begin(); owner.recordScroll(-1f)
        assertFalse(owner.drawerOwned)
        assertTrue(owner.contentOwned)
    }

    @Test fun edgeTravelMustBeRetracedIncludingPartialCrossingAndBothEdges() {
        val owner = ScrollGestureOwnership()
        owner.begin(); owner.recordScroll(1f)
        owner.recordBoundary(30f); owner.recordBoundary(20f)
        assertEquals(-20f, owner.consumeRetrace(-20f), 0f)
        assertEquals(-30f, owner.consumeRetrace(-40f), 0f)
        assertEquals(0f, owner.consumeRetrace(-10f), 0f)
        owner.recordBoundary(-50f)
        assertEquals(25f, owner.consumeRetrace(25f), 0f)
        owner.blockHandoff()
        assertEquals(0f, owner.consumeRetrace(25f), 0f)
        owner.end(); owner.begin(); owner.recordScroll(1f)
        assertEquals(0f, owner.consumeRetrace(25f), 0f)
    }

    @Test fun freshEdgeMayHandoffButPartialContentConsumptionLocksTheContact() {
        val owner = ScrollGestureOwnership()
        owner.begin()
        owner.recordScroll(0f)
        assertTrue(owner.mayHandoff)
        owner.recordScroll(1f)
        assertFalse(owner.mayHandoff)
        owner.recordScroll(0f)
        assertTrue(owner.contentOwned)
    }

    @Test fun reversalCannotClearOwnershipAndOnlyANewContactResetsIt() {
        val owner = ScrollGestureOwnership()
        owner.begin()
        owner.recordScroll(-40f)
        owner.recordScroll(40f)
        assertFalse(owner.mayHandoff)
        owner.end()
        assertTrue(owner.contentOwned)
        assertFalse(owner.mayHandoff)
        owner.begin()
        assertTrue(owner.mayHandoff)
    }

    @Test fun multiplePointersBlockHandoffUntilEveryFingerLifts() {
        val owner = ScrollGestureOwnership()
        owner.begin()
        owner.blockHandoff()
        owner.recordScroll(0f)
        assertFalse(owner.mayHandoff)
        owner.end()
        assertFalse(owner.mayHandoff)
        owner.begin()
        assertTrue(owner.mayHandoff)
    }

    @Test fun animationAndFlingWithoutAContactCannotStartAHandoff() {
        val owner = ScrollGestureOwnership()
        owner.recordScroll(40f)
        assertFalse(owner.mayHandoff)
        assertFalse(owner.contentOwned)
        owner.begin()
        owner.end()
        assertFalse(owner.mayHandoff)
    }

    @Test fun childCancellationPreFlingCannotCommitBeforeOrAfterParentCancellation() {
        val owner = ScrollGestureOwnership()
        owner.begin()
        // The child's cancellation dispatches pre-fling before the parent sees cancel.
        assertFalse(owner.mayCommitPull)
        owner.end()
        assertFalse(owner.mayCommitPull)
        owner.begin()
        assertTrue(owner.mayHandoff)
        assertFalse(owner.mayCommitPull)
        owner.release()
        assertTrue(owner.mayCommitPull)
        owner.end()
        assertTrue(owner.mayCommitPull)
    }

    @Test fun onlyRealReleaseAllowsCommitAndANewContactClearsThatPermission() {
        val owner = ScrollGestureOwnership()
        owner.begin()
        owner.release()
        // Child pre-fling may run before or after the parent's Main-pass cleanup.
        assertTrue(owner.mayCommitPull)
        owner.end()
        assertTrue(owner.mayCommitPull)
        owner.begin()
        assertFalse(owner.mayCommitPull)
        owner.end()
        assertFalse(owner.mayCommitPull)
    }

    @Test fun releaseCannotCommitContentOwnedOrMultiplePointerContacts() {
        val owner = ScrollGestureOwnership()
        owner.begin()
        owner.recordScroll(1f)
        owner.release()
        owner.end()
        assertFalse(owner.mayCommitPull)
        owner.begin()
        owner.blockHandoff()
        owner.release()
        owner.end()
        assertFalse(owner.mayCommitPull)
    }
}
