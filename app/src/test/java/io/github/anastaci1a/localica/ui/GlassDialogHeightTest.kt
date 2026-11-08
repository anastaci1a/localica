package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test

class GlassDialogHeightTest {
    @Test fun contentGrowthKeepsItsDestinationAndItsExistingRenderedHeight() {
        val sizing = GlassDialogHeight.Content
        assertNull(sizing.fixedViewportHeight(700))
        assertEquals(300f, sizing.expandedHeight(measuredHeight = 350, easedHeight = 300f), 0f)
        assertEquals(350f, sizing.expandedHeight(measuredHeight = 350, easedHeight = 350f), 0f)
    }

    @Test fun nativeViewportNeverSubtractsAContentDeltaFromAnExistingSpring() {
        val content = GlassDialogHeight.Content
        assertEquals(300f, content.expandedHeight(measuredHeight = 600, easedHeight = 300f), 0f)
        val viewport = GlassDialogHeight.ViewportFraction(1f)
        val measured = viewport.fixedViewportHeight(600)!!
        for (oldHeight in listOf(-1f, 300f, 1000f))
            assertEquals(600f, viewport.expandedHeight(measured, oldHeight), 0f)
    }

    @Test fun editorFractionPreservesPhysicalHeightAndReturnsWithoutAStaleBasis() {
        val sizing = GlassDialogHeight.ViewportFraction(.94f)
        assertEquals(1045, sizing.fixedViewportHeight(1112))
        assertEquals(560, sizing.fixedViewportHeight(596))
        assertEquals(1045, sizing.fixedViewportHeight(1112))
        assertEquals(1045f, sizing.expandedHeight(1045, 560f), 0f)
    }

    @Test fun anEmptyViewportDoesNotInventAPixelOfContent() {
        val sizing = GlassDialogHeight.ViewportFraction(.94f)
        assertEquals(0, sizing.fixedViewportHeight(0))
        assertEquals(0, sizing.fixedViewportHeight(-1))
    }

    @Test fun contentFirstMeasurementDoesNotWaitForAnEasingValue() {
        assertEquals(350f, GlassDialogHeight.Content.expandedHeight(350, -1f), 0f)
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
