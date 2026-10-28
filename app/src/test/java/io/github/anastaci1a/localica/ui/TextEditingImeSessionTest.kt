package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test

class TextEditingImeSessionTest {
    @Test fun inputTraceUsesOneMonotonicCoroutineIdAcrossMultipleConnections() {
        val events = mutableListOf<String>()
        val trace = TextEditingInputTrace { events += it }
        val first = trace.start()
        trace.created(first)
        trace.created(first)
        trace.retire(first)
        val second = trace.start()
        assertTrue(second > first)
        assertEquals(listOf("inputSession phase=start generation=$first",
            "inputSession phase=create generation=$first", "inputSession phase=create generation=$first",
            "inputSession phase=retire generation=$first", "inputSession phase=start generation=$second"), events)
    }

    @Test fun inputTraceRetirementCannotRelabelANewerOrDelayedConnection() {
        val events = mutableListOf<String>()
        val trace = TextEditingInputTrace { events += it }
        val old = trace.start()
        val current = trace.start()
        trace.retire(old)
        trace.created(current)
        trace.created(old)
        assertEquals("inputSession phase=create generation=$current", events[3])
        assertEquals("inputSession phase=create generation=$old", events[4])
    }

    @Test fun inputTraceConsumerFailureCannotInterruptInputOrRetirement() {
        val trace = TextEditingInputTrace { error("Diagnostic consumer failure") }
        val generation = trace.start()
        trace.created(generation)
        trace.retire(generation)
        assertTrue(trace.start() > generation)
    }

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

    // API30+ boundary: unknown/false native visibility cannot confirm exposure.
    // These tests exercise the pure model, not Android root-insets acquisition.
    private fun TextEditingImeSession.zeroFrame(visible: Boolean, nativeVisible: Boolean?,
        empty: Boolean, focused: Boolean = true, windowFocused: Boolean = true) =
        shouldEnd(focused, windowFocused, visible, 0, 0, 0, endAtHideStart = empty,
            zeroHeightConfirmed = nativeVisible == true)

    @Test fun nonoverlappingNativeThenComposeVisibilityNeverArmsZeroHeight() {
        for (empty in listOf(false, true)) {
            val session = TextEditingImeSession()
            // Exact false-exposure ordering: no sample has both streams visible.
            assertFalse(session.zeroFrame(false, true, empty))
            assertFalse(session.zeroFrame(true, false, empty))
            assertFalse(session.zeroFrame(false, false, empty))
            assertFalse(session.zeroFrame(false, null, empty))
        }
    }

    @Test fun confirmedFloatingExposureArmsInEitherObservationOrderAndEndsOnce() {
        for (empty in listOf(false, true)) for (nativeFirst in listOf(false, true)) {
            val session = TextEditingImeSession()
            assertFalse(session.zeroFrame(visible = !nativeFirst, nativeVisible = nativeFirst, empty = empty))
            // Compose-first case: ONLY native evidence changes on this sample.
            // The bridge must reevaluate rather than await a Compose-key change.
            assertFalse(session.zeroFrame(true, true, empty))
            assertFalse(session.zeroFrame(true, true, empty))
            assertTrue(session.zeroFrame(false, false, empty))
            assertFalse(session.zeroFrame(false, false, empty))
        }
    }

    @Test fun falseOrUnknownNativeEvidenceNeverArmsAnUnshownZeroHeightSession() {
        for (empty in listOf(false, true)) for (native in listOf<Boolean?>(false, null)) {
            val session = TextEditingImeSession()
            assertFalse(session.zeroFrame(true, native, empty))
            assertFalse(session.zeroFrame(true, native, empty))
            assertFalse(session.zeroFrame(false, native, empty))
        }
    }

    @Test fun lostNativeConfirmationDoesNotChangeAnAlreadyArmedDismissal() {
        for (empty in listOf(false, true)) for (native in listOf<Boolean?>(false, null)) {
            val session = TextEditingImeSession()
            assertFalse(session.zeroFrame(true, true, empty))
            assertFalse(session.zeroFrame(true, native, empty))
            // Confirmation gates arming only, never an existing falling edge.
            assertTrue(session.zeroFrame(false, native, empty))
            assertFalse(session.zeroFrame(false, native, empty))
        }
    }

    @Test fun focusOrCoverageResetRequiresFreshSameSampleFloatingConfirmation() {
        for (empty in listOf(false, true)) for (loseWindow in listOf(false, true)) {
            val session = TextEditingImeSession()
            assertFalse(session.zeroFrame(true, true, empty))
            assertFalse(session.zeroFrame(true, true, empty,
                focused = loseWindow, windowFocused = !loseWindow))
            // Returning focus with stale/unknown native data must not re-arm.
            assertFalse(session.zeroFrame(true, null, empty))
            assertFalse(session.zeroFrame(false, false, empty))
            // An already-visible floating keyboard works again when confirmed.
            assertFalse(session.zeroFrame(true, true, empty))
            assertTrue(session.zeroFrame(false, false, empty))
            assertFalse(session.zeroFrame(false, false, empty))
        }
    }

    @Test fun unconfirmedZeroHeightDoesNotAlterDockedArmingOrDismissalTiming() {
        for (empty in listOf(false, true)) {
            val session = TextEditingImeSession()
            fun docked(current: Int, source: Int, target: Int, visible: Boolean = true) =
                session.shouldEnd(true, true, visible, current, source, target,
                    endAtHideStart = empty, zeroHeightConfirmed = false)
            assertFalse(docked(300, 300, 300))
            assertFalse(docked(0, 300, 300, visible = false))
            assertEquals(empty, docked(280, 300, 0))
            assertEquals(!empty, docked(0, 0, 0, visible = false))
            assertFalse(docked(0, 0, 0, visible = false))
        }
    }

}

