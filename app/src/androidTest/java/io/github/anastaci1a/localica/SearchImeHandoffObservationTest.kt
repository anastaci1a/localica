package io.github.anastaci1a.localica

import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.*
import org.junit.Test

class SearchImeHandoffObservationTest {
    private fun insets(bottom: Int, visible: Boolean = bottom > 0) = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottom))
        .setVisible(WindowInsetsCompat.Type.ime(), visible).build()
    private fun animation(type: Int = WindowInsetsCompat.Type.ime()) = WindowInsetsAnimationCompat(type, null, 160)

    @Test fun anOldShowFrameCannotEraseANewerHideOrItsAppliedDestination() {
        val state = SearchImeHandoffObservation()
        val show = animation()
        val hide = animation()
        state.apply(insets(867))
        state.prepare(show)
        state.apply(insets(0))
        state.prepare(hide)
        state.progress(insets(867), listOf(show))
        state.end(show)
        assertEquals("The newer hide still owns the handoff", 1, state.animationCount)
        assertFalse(state.complete(true, insets(0)))
        state.progress(insets(0), listOf(hide))
        state.end(hide)
        assertTrue(state.complete(true, insets(0)))
    }

    @Test fun anOldShowEndingLastCannotReplaceTheLatestNativeHiddenApply() {
        val state = SearchImeHandoffObservation()
        val show = animation()
        val hide = animation()
        state.apply(insets(867))
        state.prepare(show)
        state.apply(insets(0))
        state.prepare(hide)
        state.progress(insets(0), listOf(hide))
        state.end(hide)
        assertFalse(state.complete(true, insets(0)))
        state.progress(insets(867), listOf(show))
        state.end(show)
        assertEquals(867, state.animated?.getInsets(WindowInsetsCompat.Type.ime())?.bottom)
        assertEquals(0, state.applied?.getInsets(WindowInsetsCompat.Type.ime())?.bottom)
        assertTrue(state.complete(true, insets(0)))
    }

    @Test fun nativeVisibilityFocusAndAppliedInsetsRemainRequired() {
        val state = SearchImeHandoffObservation()
        assertFalse(state.complete(true, insets(0)))
        state.apply(insets(0))
        assertFalse(state.complete(false, insets(0)))
        assertFalse(state.complete(true, null))
        assertFalse(state.complete(true, insets(867)))
        // Legacy WindowInsetsCompat infers IME visibility from its nonzero bottom.
        if (android.os.Build.VERSION.SDK_INT >= 30)
            assertFalse("A visible floating IME is not hidden", state.complete(true, insets(0, visible = true)))
        state.apply(insets(867))
        assertFalse(state.complete(true, insets(0)))
        state.apply(insets(0))
        assertTrue(state.complete(true, insets(0)))
    }

    @Test fun progressWithoutPreparationStillOwnsItsImeAnimationButSystemBarsDoNot() {
        val state = SearchImeHandoffObservation()
        state.apply(insets(0))
        val ime = animation()
        val bars = animation(WindowInsetsCompat.Type.systemBars())
        state.prepare(bars)
        assertTrue(state.complete(true, insets(0)))
        state.progress(insets(0), listOf(ime, bars))
        assertEquals(1, state.animationCount)
        assertFalse(state.complete(true, insets(0)))
        state.end(ime)
        assertTrue(state.complete(true, insets(0)))
    }

    @Test fun nativeHideRequiresItsOwnShownSourceStartedProgressAndTerminalEnd() {
        val state = SearchImeHandoffObservation()
        val hide = animation()
        state.apply(insets(867))
        state.prepare(hide)
        state.apply(insets(0))
        assertFalse("Preparation alone is not moving hide evidence", state.progressingHide)
        state.start(hide)
        assertFalse(state.progressingHide)
        hide.fraction = .5f
        state.progress(insets(400), listOf(hide))
        assertTrue(state.progressingHide)
        assertEquals(0, state.completedHideCount)
        hide.fraction = 1f
        state.progress(insets(0), listOf(hide))
        assertFalse("Terminal zero is no longer moving hide overlap", state.progressingHide)
        assertEquals("Terminal progress still waits for native end", 0, state.completedHideCount)
        state.end(hide)
        assertEquals(1, state.completedHideCount)
        assertFalse(state.progressingHide)
    }

    @Test fun controlLossCannotInventACompletedHideOrBorrowAnotherWindowsSource() {
        val popup = SearchImeHandoffObservation()
        val activity = SearchImeHandoffObservation()
        val canceled = animation()
        popup.apply(insets(867))
        popup.prepare(canceled)
        popup.apply(insets(0))
        popup.end(canceled)
        assertEquals("Prepare then end may only be control revocation", 0, popup.completedHideCount)
        val unowned = animation()
        activity.apply(insets(0))
        activity.prepare(unowned)
        activity.start(unowned)
        unowned.fraction = 1f
        activity.progress(insets(0), listOf(unowned))
        assertFalse("This window never observed a shown source", activity.progressingHide)
        activity.end(unowned)
        assertEquals(0, activity.completedHideCount)
        val interrupted = animation()
        popup.apply(insets(867))
        popup.prepare(interrupted)
        popup.apply(insets(0))
        popup.start(interrupted)
        interrupted.fraction = .5f
        popup.progress(insets(400), listOf(interrupted))
        popup.end(interrupted)
        assertEquals("An interrupted hide cannot supply terminal hidden evidence", 0, popup.completedHideCount)
    }

    @Test fun aNewHiddenApplyCannotRelabelAnOlderResizeAsACompletedHide() {
        val state = SearchImeHandoffObservation()
        val resize = animation()
        val hide = animation()
        state.apply(insets(400))
        state.prepare(resize)
        state.apply(insets(867))
        state.start(resize)
        resize.fraction = .5f
        state.progress(insets(600), listOf(resize))
        state.prepare(hide)
        state.apply(insets(0))
        state.start(hide)
        assertFalse("The new hide has not progressed; the old resize is not a hide", state.progressingHide)
        hide.fraction = .5f
        state.progress(insets(300), listOf(hide))
        assertTrue(state.progressingHide)
        state.end(hide)
        assertFalse(state.progressingHide)
        // An API26 singleton callback for the old animation cannot inherit the
        // canceled hide's target, even when its last frame is reported as zero.
        resize.fraction = 1f
        state.progress(insets(0), listOf(resize))
        assertFalse(state.progressingHide)
        state.end(resize)
        assertEquals(0, state.completedHideCount)
    }
}
