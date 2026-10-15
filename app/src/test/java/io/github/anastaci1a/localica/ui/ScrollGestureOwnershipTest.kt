package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test

class ScrollGestureOwnershipTest {
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
}
