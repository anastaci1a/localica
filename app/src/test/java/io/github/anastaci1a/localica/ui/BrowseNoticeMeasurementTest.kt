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

    @Test fun lookaheadPassFillsMissingGeometryAndTheApproachPassAlwaysWins() {
        val state = BrowseNoticeMeasurement()
        val node = Any()
        val content = Any()
        val changed = Any()
        val density = Density(2f, 1f)
        fun height(identity: Any = content, width: Int = 200) = state.height(identity, width, density, LayoutDirection.Ltr)
        state.attach(node)
        // A canonicalized zero notice is measured only in lookahead; that is the grid's geometry.
        state.measured(node, content, 200, 0, density, LayoutDirection.Ltr, lookahead = true)
        assertEquals(0, height())
        // The approach pass of the same frame replaces it, and a repeated lookahead cannot undo it.
        state.measured(node, content, 200, 40, density, LayoutDirection.Ltr)
        assertEquals(40, height())
        state.measured(node, content, 200, 0, density, LayoutDirection.Ltr, lookahead = true)
        assertEquals("A lookahead pass never overwrites approach geometry with the same key", 40, height())
        // A nonzero lookahead followed by an approach zero certifies zero: approach always replaces.
        state.invalidate(node)
        state.measured(node, content, 200, 40, density, LayoutDirection.Ltr, lookahead = true)
        assertEquals(40, height())
        state.measured(node, content, 200, 0, density, LayoutDirection.Ltr)
        assertEquals(0, height())
        // Changed constraints or content are new geometry, so lookahead publishes again.
        state.measured(node, content, 180, 0, density, LayoutDirection.Ltr, lookahead = true)
        assertNull(height())
        assertEquals(0, height(width = 180))
        state.measured(node, changed, 180, 0, density, LayoutDirection.Ltr, lookahead = true)
        assertNull(height(width = 180))
        assertEquals(0, height(changed, 180))
        // The cached read-only receipt and the writer protocol are unchanged for lookahead receipts.
        state.detach(node, cached = true)
        state.measured(node, changed, 180, 90, density, LayoutDirection.Ltr, lookahead = true)
        assertEquals(0, height(changed, 180))
        state.attach(node)
        state.detach(node)
        assertNull(height(changed, 180))
    }
}
