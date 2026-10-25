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
}
