package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test

class TextEditingImeSessionTest {
    private fun TextEditingImeSession.frame(current: Int, source: Int, target: Int,
        visible: Boolean = true, empty: Boolean = true, focused: Boolean = true,
        windowFocused: Boolean = true) = shouldEnd(focused, windowFocused, visible,
            current, source, target, endAtHideStart = empty)

    @Test fun inheritedZeroTargetCannotArmTheNewEditor() {
        val session = TextEditingImeSession()
        assertFalse(session.frame(80, 0, 0))
        assertFalse(session.frame(0, 0, 0, visible = false))
    }

    @Test fun matchingEndpointsWithoutMatchingCurrentInsetAreNotSettled() {
        val session = TextEditingImeSession()
        assertFalse(session.frame(120, 300, 300))
        assertFalse(session.frame(90, 300, 0))
        assertFalse(session.frame(0, 0, 0, visible = false))
    }

    @Test fun emptySearchEndsOnceAtTheRealHideStart() {
        val session = TextEditingImeSession()
        assertFalse(session.frame(300, 300, 300))
        assertTrue(session.frame(280, 300, 0))
        assertFalse(session.frame(0, 0, 0, visible = false))
    }

    @Test fun nonemptySearchWaitsForTheKeyboardToHide() {
        val session = TextEditingImeSession()
        assertFalse(session.frame(300, 300, 300, empty = false))
        assertFalse(session.frame(280, 300, 0, empty = false))
        assertTrue(session.frame(0, 0, 0, visible = false, empty = false))
    }

    @Test fun emptySearchIgnoresTransientInvisibilityWhileTheTargetStillShows() {
        val session = TextEditingImeSession()
        assertFalse(session.frame(600, 600, 600))
        // API36 may momentarily lose current visibility during input handoff,
        // even after publishing equal shown current/source/target insets.
        assertFalse(session.frame(0, 600, 600, visible = false))
        assertFalse(session.frame(0, 600, 600, visible = false))
        // Do not discard the armed session: a real hide still ends it without
        // needing a second settled shown frame after the visibility gap.
        assertTrue(session.frame(580, 600, 0))
        assertFalse(session.frame(0, 0, 0, visible = false))
    }

    @Test fun nonemptySearchKeepsEditingAcrossAShownTargetVisibilityGap() {
        val session = TextEditingImeSession()
        assertFalse(session.frame(600, 600, 600, empty = false))
        assertFalse(session.frame(0, 600, 600, visible = false, empty = false))
        assertFalse(session.frame(0, 600, 400, visible = false, empty = false))
        assertFalse(session.frame(580, 600, 0, empty = false))
        assertTrue(session.frame(0, 600, 0, visible = false, empty = false))
        assertFalse(session.frame(0, 0, 0, visible = false, empty = false))
    }

    @Test fun floatingKeyboardEndsOnItsVisibilityEdgeRatherThanItsAlwaysZeroTarget() {
        val session = TextEditingImeSession()
        assertFalse(session.frame(0, 0, 0, visible = false))
        assertFalse(session.frame(0, 0, 0))
        assertFalse(session.frame(0, 0, 0))
        assertTrue(session.frame(0, 0, 0, visible = false))
    }

    @Test fun alreadyVisibleFloatingKeyboardStillEndsEditingOnBack() {
        val session = TextEditingImeSession()
        assertFalse(session.frame(0, 0, 0, focused = false))
        assertFalse(session.frame(0, 0, 0))
        assertFalse(session.frame(0, 0, 0))
        assertTrue(session.frame(0, 0, 0, visible = false))
    }

    @Test fun coveredOrUnfocusedEditorsDoNotInheritThePriorSession() {
        for (loseWindow in listOf(false, true)) {
            val session = TextEditingImeSession()
            assertFalse(session.frame(300, 300, 300))
            assertFalse(session.frame(300, 300, 300, focused = loseWindow, windowFocused = !loseWindow))
            assertFalse(session.frame(120, 300, 0))
            assertFalse(session.frame(0, 0, 0, visible = false))
        }
    }
}
