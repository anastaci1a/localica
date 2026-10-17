package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test

class SelectorTrackActivityTest {
    @Test fun focusAndMotionIndependentlyRetainStackingUntilTheirOwnersFinish() {
        val activity = SelectorTrackActivity()
        assertFalse(activity.raised)
        activity.update(true)
        assertTrue(activity.raised)
        activity.focus(0, true)
        activity.update(false)
        assertTrue(activity.raised)
        activity.focus(1, true)
        activity.focus(0, false)
        assertTrue(activity.raised)
        activity.focus(1, false)
        assertFalse(activity.raised)
    }

    @Test fun disposalClearsFocusAndPendingSettlement() {
        val activity = SelectorTrackActivity()
        activity.update(true)
        activity.focus(0, true)
        activity.clear()
        assertFalse(activity.raised)
    }

    @Test fun narrowSingleTabRestUsesItsEffectiveClampedCorners() {
        for (density in listOf(1f, 1.5f, 2f, 2.625f, 3f)) {
            for (widthDp in 48..63) {
                val height = 64f * density
                val inset = 4f * density
                val geometry = selectorTrackGeometry(widthDp * density, height, 0f, 1f,
                    false, false, false, overflow = false, maximumExtension = 4f * density)
                val insets = SelectorTrackInsets(0f, inset, inset)
                val effectiveRest = geometry.lens(0f, 1, insets, false, false,
                    GlassShape(), height / 2f).crossFrame(geometry)
                val idealRest = SelectorCrossFrame(height, height - 2f * inset,
                    height / 2f - inset, height / 2f)
                assertNotEquals("Fixture must exercise the narrow-host clamp", idealRest, effectiveRest)
                val activity = SelectorTrackActivity()
                activity.update(true)
                // Same geometry-owned rest used by SlidingTabs after all body clocks settle.
                val rawLens = geometry.lens(0f, 1, insets, false, false, GlassShape(), height / 2f)
                val rawCross = rawLens.crossFrame(geometry)
                val displayed = rawCross.copy(
                    corner = minOf(rawCross.corner, rawLens.bounds.width / 2f, rawCross.lens / 2f),
                    outerCorner = minOf(rawCross.outerCorner, geometry.travelExtent / 2f, rawCross.shell / 2f))
                activity.update(displayed != effectiveRest)
                assertFalse("${widthDp}dp at density $density must relinquish stacking", activity.raised)
                activity.focus(0, true)
                assertTrue("Keyboard focus retains its independent ownership", activity.raised)
            }
        }
    }
}
