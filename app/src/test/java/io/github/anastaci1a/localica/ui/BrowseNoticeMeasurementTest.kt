package io.github.anastaci1a.localica.ui

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.*
import org.junit.Test

/** Adversarial writer protocol: cached native geometry is readable, retired callbacks cannot publish. */
class BrowseNoticeMeasurementTest {
    @Test fun resetRevokesWritesImmediatelyAndOnlyCompatibleCachedReadsSurviveReuse() {
        val state = BrowseNoticeMeasurement()
        val first = Any()
        val second = Any()
        val content = Any()
        val changed = Any()
        val density = Density(2f, 1f)
        fun height(identity: Any = content) = state.height(identity, 200, density, LayoutDirection.Ltr)
        state.attach(first)
        state.measured(first, content, 200, 0, density, LayoutDirection.Ltr)
        assertEquals(0, height())

        // onReset revokes before the subsequent onDetach: simulate an obsolete callback in between.
        state.detach(first, cached = true)
        state.measured(first, content, 200, 100, density, LayoutDirection.Ltr)
        assertEquals("A reset writer cannot overwrite its immutable zero receipt", 0, height())
        state.detach(first, cached = true)
        assertEquals(0, height())
        assertNull(height(changed))
        assertNull(state.height(content, 201, density, LayoutDirection.Ltr))
        assertNull(state.height(content, 200, Density(3f, 1f), LayoutDirection.Ltr))
        assertNull(state.height(content, 200, Density(2f, 1.2f), LayoutDirection.Ltr))
        assertNull(state.height(content, 200, density, LayoutDirection.Rtl))

        state.attach(second)
        state.measured(first, content, 200, 100, density, LayoutDirection.Ltr)
        assertEquals("A recycled old writer cannot publish into the current attachment", 0, height())
        state.measured(second, changed, 200, 100, density, LayoutDirection.Ltr)
        assertNull(height())
        assertEquals(100, height(changed))
        state.detach(first)
        assertEquals("A late old detach cannot clear the new receipt", 100, height(changed))
        state.detach(second)
        assertNull("Direct detach drops the receipt", height(changed))
        state.attach(first)
        assertNull("Reattach cannot resurrect geometry dropped by a real detach", height(changed))
        state.measured(first, content, 200, 0, density, LayoutDirection.Ltr)
        assertEquals(0, height())
    }
}
