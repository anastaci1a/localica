package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.ui.spec.GlassDialogHeight
import io.github.anastaci1a.localica.ui.spec.fixedViewportHeight
import org.junit.Assert.*
import org.junit.Test

class GlassDialogHeightTest {
    @Test fun contentDoesNotAcquireAFixedViewportExtent() {
        for (available in listOf(0, 300, 350, 600, 1000))
            assertNull(GlassDialogHeight.Content.fixedViewportHeight(available))
    }

    @Test fun editorFractionRoundsOnceAndReturnsToItsOriginalExtent() {
        val sizing = GlassDialogHeight.ViewportFraction(.94f)
        assertEquals(1045, sizing.fixedViewportHeight(1112))
        assertEquals(560, sizing.fixedViewportHeight(596))
        assertEquals(1045, sizing.fixedViewportHeight(1112))
    }

    @Test fun emptyAndTinyViewportsDoNotInventAPixelOfContent() {
        val sizing = GlassDialogHeight.ViewportFraction(.94f)
        assertEquals(0, sizing.fixedViewportHeight(0))
        assertEquals(0, sizing.fixedViewportHeight(-1))
        assertEquals(1, sizing.fixedViewportHeight(1))
    }

    @Test fun viewportFractionRejectsInvalidContracts() {
        for (fraction in listOf(0f, -1f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            try {
                GlassDialogHeight.ViewportFraction(fraction)
                fail("Accepted invalid fraction $fraction")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
